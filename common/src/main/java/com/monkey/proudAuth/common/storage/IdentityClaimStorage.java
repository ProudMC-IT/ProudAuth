package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IdentityClaimStorage {

    CompletableFuture<Optional<IdentityClaimRecord>> findIdentityClaim(String username);

    CompletableFuture<Void> deleteIdentityClaim(String username);

    CompletableFuture<Void> saveIdentityClaim(String username, AccountType claimType, String claimedIp, Instant claimedAt);

    CompletableFuture<Optional<PendingIdentityClaimRecord>> findPendingIdentityClaim(String username, String ipAddress);

    CompletableFuture<Optional<PendingIdentityClaimRecord>> findLatestPendingIdentityClaim(String username);

    CompletableFuture<Void> savePendingIdentityClaim(
            String username,
            AccountType claimType,
            String ipAddress,
            Instant createdAt,
            Instant expiresAt
    );

    CompletableFuture<Void> deletePendingIdentityClaim(String username);

    CompletableFuture<Integer> deleteExpiredPendingIdentityClaims();
}
