package com.monkey.proudAuth.common.storage;

import java.util.concurrent.CompletableFuture;

public interface StatsStorage {

    CompletableFuture<StatsSnapshot> fetchStats();
}
