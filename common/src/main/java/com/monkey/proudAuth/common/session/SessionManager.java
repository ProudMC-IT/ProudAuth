package com.monkey.proudAuth.common.session;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.Session;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SessionManager {

    CompletableFuture<Optional<Session>> findValid(UUID uuid, String ip);

    CompletableFuture<Session> create(UUID uuid, String ip);

    CompletableFuture<Void> invalidate(UUID uuid);

    CompletableFuture<Void> invalidate(String token);

    CompletableFuture<Integer> cleanupExpired();

    void reload(ProudAuthSettings settings);
}
