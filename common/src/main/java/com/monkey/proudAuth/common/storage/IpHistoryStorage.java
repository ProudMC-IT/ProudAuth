package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface IpHistoryStorage {

    CompletableFuture<Void> saveIpHistory(String username, String ipAddress, AccountType accountType, Instant observedAt);

    CompletableFuture<Integer> countDistinctUsernamesForIpSince(String ipAddress, Instant since);

    CompletableFuture<Integer> countDistinctIpsForUsernameSince(String username, Instant since);

    CompletableFuture<Integer> deleteIpHistoryOlderThan(Instant cutoff);

    CompletableFuture<List<HistoryEntry>> listRecentHistoryByUsername(String username, int limit);

    CompletableFuture<List<IpSummary>> topIpsByDistinctUsernamesSince(Instant since, int limit);

    CompletableFuture<List<UserSummary>> topUsersByDistinctIpsSince(Instant since, int limit);

    record IpSummary(String ipAddress, int distinctUsernames, int totalHits) {
    }

    record UserSummary(String username, int distinctIps, int totalHits) {
    }

    record HistoryEntry(String ipAddress, AccountType accountType, Instant observedAt) {
    }
}
