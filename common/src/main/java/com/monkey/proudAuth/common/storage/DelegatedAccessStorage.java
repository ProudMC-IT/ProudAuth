package com.monkey.proudAuth.common.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DelegatedAccessStorage {

    CompletableFuture<Void> saveDelegatedAccessGrant(DelegatedAccessGrantRecord grant);

    CompletableFuture<Optional<DelegatedAccessGrantRecord>> findDelegatedAccessGrant(UUID ownerUuid, UUID delegateUuid);

    CompletableFuture<List<DelegatedAccessGrantRecord>> listDelegatedAccessGrants(UUID ownerUuid);

    CompletableFuture<Boolean> revokeDelegatedAccessGrant(UUID ownerUuid, UUID delegateUuid);
}
