package com.monkey.proudAuth.velocity.bridge;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.BackendJoinProbeStorage;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class VelocityBackendJoinProbeService {

    private static final long MIN_TIMEOUT_MS = 250L;
    private static final long MIN_POLL_INTERVAL_MS = 25L;
    private static final long EXPIRES_GRACE_MS = 750L;

    private final Supplier<BackendJoinProbeStorage> storageSupplier;
    private final Supplier<ProudAuthNetworkConfig> settingsSupplier;
    private final Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityBackendJoinProbeService(
            Supplier<BackendJoinProbeStorage> storageSupplier,
            Supplier<ProudAuthNetworkConfig> settingsSupplier,
            Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.settingsSupplier = settingsSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    public ProbeStatus probe(String username, String ipAddress, String targetServer) {
        ProudAuthNetworkConfig settings = settingsSupplier.get();
        if (!settings.bridge().enabled()
                || !settings.proxy().bridgeBackendCheck().enabled()
                || targetServer == null
                || targetServer.isBlank()) {
            return ProbeStatus.SKIPPED;
        }

        String probeId = UUID.randomUUID().toString().replace("-", "");
        long timeoutMs = Math.max(MIN_TIMEOUT_MS, settings.proxy().bridgeBackendCheck().timeoutMs());
        long pollIntervalMs = Math.max(MIN_POLL_INTERVAL_MS, Math.min(timeoutMs, settings.proxy().bridgeBackendCheck().pollIntervalMs()));
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(timeoutMs + EXPIRES_GRACE_MS);
        String canonicalUsername = username.toLowerCase(Locale.ROOT);

        try {
            storageSupplier.get()
                    .createBackendJoinProbe(probeId, canonicalUsername, ipAddress, targetServer, issuedAt, expiresAt)
                    .join();
            debugEvent("backend_probe_created",
                    "player", username,
                    "ip", ipAddress,
                    "target_server", targetServer,
                    "probe_id", probeId,
                    "timeout_ms", timeoutMs,
                    "poll_ms", pollIntervalMs);

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                boolean acknowledged = storageSupplier.get().isBackendJoinProbeAcknowledged(probeId).join();
                if (acknowledged) {
                    debugEvent("backend_probe_acknowledged",
                            "player", username,
                            "target_server", targetServer,
                            "probe_id", probeId);
                    return ProbeStatus.ACKNOWLEDGED;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    debugEvent("backend_probe_timeout",
                            "player", username,
                            "target_server", targetServer,
                            "probe_id", probeId);
                    return ProbeStatus.TIMEOUT;
                }

                long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                long sleepMs = Math.max(1L, Math.min(pollIntervalMs, remainingMs));
                Thread.sleep(sleepMs);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            debugEvent("backend_probe_interrupted",
                    "player", username,
                    "target_server", targetServer,
                    "probe_id", probeId);
            return ProbeStatus.ERROR;
        } catch (Exception exception) {
            debugEvent("backend_probe_error",
                    "player", username,
                    "target_server", targetServer,
                    "probe_id", probeId,
                    "error", exception.getMessage());
            return ProbeStatus.ERROR;
        } finally {
            try {
                storageSupplier.get().deleteBackendJoinProbe(probeId).join();
                debugEvent("backend_probe_cleaned",
                        "player", username,
                        "target_server", targetServer,
                        "probe_id", probeId);
            } catch (Exception exception) {
                debugEvent("backend_probe_cleanup_error",
                        "player", username,
                        "target_server", targetServer,
                        "probe_id", probeId,
                        "error", exception.getMessage());
            }
        }
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }

    public enum ProbeStatus {
        SKIPPED,
        ACKNOWLEDGED,
        TIMEOUT,
        ERROR
    }
}
