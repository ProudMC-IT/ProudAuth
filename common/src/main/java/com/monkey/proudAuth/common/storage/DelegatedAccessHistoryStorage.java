package com.monkey.proudAuth.common.storage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DelegatedAccessHistoryStorage {

    CompletableFuture<Void> saveDelegatedAccessHistory(DelegatedAccessHistoryRecord record);

    CompletableFuture<List<DelegatedAccessHistoryRecord>> listDelegatedAccessHistory(UUID accountUuid, DelegatedAccessHistoryDirection direction, int page, int pageSize);

    CompletableFuture<Integer> countDelegatedAccessHistory(UUID accountUuid, DelegatedAccessHistoryDirection direction);

    CompletableFuture<Integer> deleteDelegatedAccessHistoryBefore(Instant cutoff);
}
