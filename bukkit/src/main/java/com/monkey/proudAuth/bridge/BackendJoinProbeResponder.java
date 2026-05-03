package com.monkey.proudAuth.bridge;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.BackendJoinProbeStorage;

import java.util.function.Supplier;

public final class BackendJoinProbeResponder {

    private static final int DEFAULT_BATCH_SIZE = 250;
    private static final int CLEANUP_FREQUENCY = 120;

    private final Supplier<BackendJoinProbeStorage> storageSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;
    private final String responderId;
    private final String serverId;
    private int runCounter;

    public BackendJoinProbeResponder(
            Supplier<BackendJoinProbeStorage> storageSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger,
            String responderId,
            String serverId
    ) {
        this.storageSupplier = storageSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
        this.responderId = responderId;
        this.serverId = serverId;
        this.runCounter = 0;
    }

    public void poll() {
        try {
            int acknowledged = storageSupplier.get()
                    .acknowledgePendingBackendJoinProbes(responderId, serverId, DEFAULT_BATCH_SIZE)
                    .join();
            if (acknowledged > 0) {
                debugEvent("backend_probe_ack",
                        "responder", responderId,
                        "server_id", serverId,
                        "acknowledged", acknowledged);
            }

            runCounter++;
            if (runCounter >= CLEANUP_FREQUENCY) {
                runCounter = 0;
                int deleted = storageSupplier.get().deleteExpiredBackendJoinProbes().join();
                if (deleted > 0) {
                    debugEvent("backend_probe_cleanup",
                            "responder", responderId,
                            "server_id", serverId,
                            "deleted", deleted);
                }
            }
        } catch (Exception exception) {
            logger.error("Errore durante la risposta ai backend join probe.", exception);
        }
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }
}
