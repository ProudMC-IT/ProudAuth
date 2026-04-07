package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.Session;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Session persistence contract used by session management.
 */
public interface SessionStorage {

    CompletableFuture<Optional<Session>> findValidSession(UUID uuid, @Nullable String ip);

    CompletableFuture<Void> saveSession(Session session);

    CompletableFuture<Void> deleteSessions(UUID uuid);

    CompletableFuture<Void> deleteSession(String token);

    CompletableFuture<Integer> deleteExpiredSessions();
}
