package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.Session;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StorageProvider extends AutoCloseable {

    void init();

    void reload(ProudAuthSettings settings);

    CompletableFuture<Optional<AccountRecord>> findAccountByUuid(UUID uuid);

    CompletableFuture<Optional<AccountRecord>> findAccountByUsername(String username);

    CompletableFuture<Void> saveAccount(AccountRecord accountRecord);

    CompletableFuture<Void> updatePassword(UUID uuid, String passwordHash);

    CompletableFuture<Void> updatePasswordByUsername(String username, String passwordHash);

    CompletableFuture<Void> touchLogin(UUID uuid, String username, String ip, AccountType accountType);

    CompletableFuture<Void> updateTotpSecret(UUID uuid, @Nullable String encryptedSecret);

    CompletableFuture<Optional<String>> findTotpSecret(UUID uuid);

    CompletableFuture<Optional<Session>> findValidSession(UUID uuid, @Nullable String ip);

    CompletableFuture<Void> saveSession(Session session);

    CompletableFuture<Void> deleteSessions(UUID uuid);

    CompletableFuture<Void> deleteSession(String token);

    CompletableFuture<Integer> deleteExpiredSessions();

    CompletableFuture<Void> saveProxyAssertion(ProxyBridgeAssertion assertion);

    CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertion(String username, String ip);

    CompletableFuture<Void> deleteProxyAssertion(String nonce);

    CompletableFuture<Integer> deleteExpiredProxyAssertions();

    CompletableFuture<Optional<IpBanRecord>> findActiveBan(String ip);

    CompletableFuture<Void> banIp(String ip, Instant expiresAt, @Nullable String reason);

    CompletableFuture<Void> unbanIp(String ip);

    CompletableFuture<StatsSnapshot> fetchStats();

    @Override
    void close();

    record AccountRecord(
            UUID uuid,
            String username,
            @Nullable String passwordHash,
            AccountType accountType,
            @Nullable String email,
            @Nullable String totpSecret,
            Instant registeredAt,
            @Nullable Instant lastLoginAt,
            @Nullable String lastIp
    ) {
    }

    record IpBanRecord(String ip, Instant expiresAt, @Nullable String reason) {
    }

    record StatsSnapshot(long registeredAccounts, long premiumAccounts, long activeSessions) {
    }
}
