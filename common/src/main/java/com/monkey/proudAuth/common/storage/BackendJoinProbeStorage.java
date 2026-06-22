package com.monkey.proudAuth.common.storage;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public interface BackendJoinProbeStorage {

    CompletableFuture<Void> createBackendJoinProbe(
            String probeId,
            String username,
            String ipAddress,
            String targetRegion,
            String targetServer,
            Instant issuedAt,
            Instant expiresAt
    );

    CompletableFuture<Boolean> isBackendJoinProbeAcknowledged(String probeId);

    CompletableFuture<Integer> acknowledgePendingBackendJoinProbes(String responderId, String regionId, String serverId, int maxBatchSize);

    CompletableFuture<Void> deleteBackendJoinProbe(String probeId);

    CompletableFuture<Integer> deleteExpiredBackendJoinProbes();
}
