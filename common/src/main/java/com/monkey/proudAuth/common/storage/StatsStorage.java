package com.monkey.proudAuth.common.storage;

import java.util.concurrent.CompletableFuture;

/**
 * Read-only storage contract for admin statistics.
 */
public interface StatsStorage {

    CompletableFuture<StatsSnapshot> fetchStats();
}
