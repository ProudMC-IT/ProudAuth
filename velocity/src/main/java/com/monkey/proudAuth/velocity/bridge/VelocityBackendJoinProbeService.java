package com.monkey.proudAuth.velocity.bridge;

import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.BackendJoinProbeStorage;
import com.monkey.proudAuth.velocity.config.VelocityPluginSettings;

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
    private final Supplier<VelocityPluginSettings.Bridge> bridgeSettingsSupplier;
    private final Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityBackendJoinProbeService(
            Supplier<BackendJoinProbeStorage> storageSupplier,
            Supplier<VelocityPluginSettings.Bridge> bridgeSettingsSupplier,
            Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.bridgeSettingsSupplier = bridgeSettingsSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    public ProbeStatus probe(String username, String ipAddress) {
        VelocityPluginSettings.Bridge bridge = bridgeSettingsSupplier.get();
        if (!bridge.enabled() || !bridge.backendCheckEnabled()) {
            return ProbeStatus.SKIPPED;
        }

        String probeId = UUID.randomUUID().toString().replace("-", "");
        long timeoutMs = Math.max(MIN_TIMEOUT_MS, bridge.backendCheckTimeoutMs());
        long pollIntervalMs = Math.max(MIN_POLL_INTERVAL_MS, Math.min(timeoutMs, bridge.backendCheckPollIntervalMs()));
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(timeoutMs + EXPIRES_GRACE_MS);
        String canonicalUsername = username.toLowerCase(Locale.ROOT);

        try {
            storageSupplier.get()
                    .createBackendJoinProbe(probeId, canonicalUsername, ipAddress, issuedAt, expiresAt)
                    .join();
            debug("Velocity backend probe created player=%s ip=%s probeId=%s timeoutMs=%s pollMs=%s",
                    username,
                    ipAddress,
                    probeId,
                    timeoutMs,
                    pollIntervalMs);

            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                boolean acknowledged = storageSupplier.get().isBackendJoinProbeAcknowledged(probeId).join();
                if (acknowledged) {
                    debug("Velocity backend probe acknowledged player=%s probeId=%s", username, probeId);
                    return ProbeStatus.ACKNOWLEDGED;
                }

                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    debug("Velocity backend probe timeout player=%s probeId=%s", username, probeId);
                    return ProbeStatus.TIMEOUT;
                }

                long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                long sleepMs = Math.max(1L, Math.min(pollIntervalMs, remainingMs));
                Thread.sleep(sleepMs);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            debug("Velocity backend probe interrupted player=%s probeId=%s", username, probeId);
            return ProbeStatus.ERROR;
        } catch (Exception exception) {
            debug("Velocity backend probe error player=%s probeId=%s error=%s",
                    username,
                    probeId,
                    exception.getMessage());
            return ProbeStatus.ERROR;
        } finally {
            try {
                storageSupplier.get().deleteBackendJoinProbe(probeId).join();
                debug("Velocity backend probe cleaned player=%s probeId=%s", username, probeId);
            } catch (Exception exception) {
                debug("Velocity backend probe cleanup error player=%s probeId=%s error=%s",
                        username,
                        probeId,
                        exception.getMessage());
            }
        }
    }

    private void debug(String template, Object... args) {
        logger.debug(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, template, args);
    }

    public enum ProbeStatus {
        SKIPPED,
        ACKNOWLEDGED,
        TIMEOUT,
        ERROR
    }
}
