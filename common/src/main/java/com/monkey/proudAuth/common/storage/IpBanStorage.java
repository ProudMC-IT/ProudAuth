package com.monkey.proudAuth.common.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IpBanStorage {

    CompletableFuture<Optional<IpBanRecord>> findActiveBan(String ip);

    CompletableFuture<List<String>> listActiveBannedIps(String prefix, int limit);

    CompletableFuture<Void> banIp(String ip, Instant expiresAt, @Nullable String reason);

    CompletableFuture<Void> unbanIp(String ip);
}
