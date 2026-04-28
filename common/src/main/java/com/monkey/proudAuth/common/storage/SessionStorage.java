package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.Session;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SessionStorage {

    CompletableFuture<Optional<Session>> findValidSession(UUID uuid, @Nullable String ip, AccountType expectedAccountType);

    CompletableFuture<java.util.List<Session>> listSessions(UUID uuid, boolean onlyActive);

    CompletableFuture<Void> saveSession(Session session);

    CompletableFuture<Void> deleteSessions(UUID uuid);

    CompletableFuture<Integer> deleteSessionsByIp(String ip);

    CompletableFuture<Void> deleteSessionsByUuidAndNotAccountType(UUID uuid, AccountType keepType);

    CompletableFuture<Void> deleteSession(String token);

    CompletableFuture<Integer> deleteExpiredSessions();
}
