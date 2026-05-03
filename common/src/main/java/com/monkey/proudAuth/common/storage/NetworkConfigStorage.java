package com.monkey.proudAuth.common.storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface NetworkConfigStorage {

    CompletableFuture<Optional<NetworkConfigSnapshotRecord>> fetchActiveNetworkConfig();

    CompletableFuture<Optional<NetworkConfigSnapshotRecord>> fetchActiveNetworkConfigState();

    CompletableFuture<NetworkConfigSnapshotRecord> saveActiveNetworkConfig(
            String document,
            String configHash,
            String updatedBy,
            String sourceNode
    );
}
