package com.monkey.proudAuth.common.storage;

import java.time.Instant;

public record NetworkConfigSnapshotRecord(
        long version,
        String configHash,
        String document,
        Instant updatedAt,
        String updatedBy,
        String sourceNode
) {
}
