package com.monkey.proudAuth.common.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence contract for active IP bans and lockouts.
 */
public interface IpBanStorage {

    CompletableFuture<Optional<IpBanRecord>> findActiveBan(String ip);

    CompletableFuture<Void> banIp(String ip, Instant expiresAt, @Nullable String reason);

    CompletableFuture<Void> unbanIp(String ip);
}
