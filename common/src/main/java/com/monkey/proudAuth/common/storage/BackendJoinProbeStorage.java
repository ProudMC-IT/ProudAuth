package com.monkey.proudAuth.common.storage;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public interface BackendJoinProbeStorage {

    CompletableFuture<Void> createBackendJoinProbe(
            String probeId,
            String username,
            String ipAddress,
            Instant issuedAt,
            Instant expiresAt
    );

    CompletableFuture<Boolean> isBackendJoinProbeAcknowledged(String probeId);

    CompletableFuture<Integer> acknowledgePendingBackendJoinProbes(String responderId, int maxBatchSize);

    CompletableFuture<Void> deleteBackendJoinProbe(String probeId);

    CompletableFuture<Integer> deleteExpiredBackendJoinProbes();
}
