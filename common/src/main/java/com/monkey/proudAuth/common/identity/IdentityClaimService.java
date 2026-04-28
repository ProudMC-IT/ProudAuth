package com.monkey.proudAuth.common.identity;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.PendingIdentityClaimRecord;
import com.monkey.proudAuth.common.storage.StorageProvider;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class IdentityClaimService {

    private final StorageProvider storage;
    private volatile ProudAuthSettings settings;

    public IdentityClaimService(StorageProvider storage, ProudAuthSettings settings) {
        this.storage = storage;
        this.settings = settings;
    }

    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    public boolean isClaimOnFirstJoinEnabled() {
        return settings.premium().enabled()
                && settings.premium().isClaimOnFirstJoin()
                && settings.proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY;
    }

    public CompletableFuture<Optional<AccountType>> resolveEffectiveClaim(String username) {
        return storage.findIdentityClaim(username)
                .thenCompose(optionalClaim -> {
                    if (optionalClaim.isPresent()) {
                        return CompletableFuture.completedFuture(Optional.of(optionalClaim.get().claimType()));
                    }
                    return storage.findAccountByUsername(username)
                            .thenCompose(optionalAccount -> promoteAccountClaim(username, optionalAccount));
                });
    }

    public CompletableFuture<Optional<PendingIdentityClaimRecord>> findPendingClaim(String username, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return storage.findPendingIdentityClaim(username, ipAddress);
    }

    public CompletableFuture<ClaimSnapshot> snapshot(String username, String ipAddress) {
        return resolveEffectiveClaim(username).thenCompose(finalClaim ->
                findPendingClaim(username, ipAddress)
                        .thenApply(optionalPending -> new ClaimSnapshot(finalClaim, optionalPending.map(PendingIdentityClaimRecord::claimType))));
    }

    public CompletableFuture<Void> beginPendingClaim(String username, AccountType claimType, String ipAddress) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(30L, settings.premium().claimPendingSeconds()));
        return storage.savePendingIdentityClaim(username, claimType, ipAddress, now, expiresAt);
    }

    public CompletableFuture<Void> finalizeClaim(String username, AccountType claimType, String ipAddress) {
        return storage.saveIdentityClaim(username, claimType, ipAddress, Instant.now())
                .thenCompose(ignored -> storage.deletePendingIdentityClaim(username));
    }

    public CompletableFuture<Void> clearPendingClaim(String username) {
        return storage.deletePendingIdentityClaim(username);
    }

    private CompletableFuture<Optional<AccountType>> promoteAccountClaim(String username, Optional<AccountRecord> optionalAccount) {
        if (optionalAccount.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        AccountRecord account = optionalAccount.get();
        return storage.saveIdentityClaim(username, account.accountType(), account.lastIp(), Instant.now())
                .thenApply(ignored -> Optional.of(account.accountType()));
    }

    public record ClaimSnapshot(Optional<AccountType> finalClaim, Optional<AccountType> pendingClaim) {
        public boolean requiresInteractiveChoice() {
            return finalClaim.isEmpty();
        }
    }
}
