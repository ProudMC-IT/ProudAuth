package com.monkey.proudAuth.common.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DelegatedAccessHistoryRecord(
        long id,
        @Nullable UUID actorUuid,
        String actorUsername,
        @Nullable UUID targetUuid,
        String targetUsername,
        String eventType,
        String result,
        String reason,
        String ipAddress,
        Instant createdAt
) {
}
