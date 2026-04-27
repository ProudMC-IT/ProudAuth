package com.monkey.proudAuth.common.session.impl;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.SessionStorage;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class SessionManagerImpl implements SessionManager {

    private final SessionStorage storage;
    private volatile ProudAuthSettings settings;

    public SessionManagerImpl(SessionStorage storage, ProudAuthSettings settings) {
        this.storage = storage;
        this.settings = settings;
    }

    @Override
    public CompletableFuture<Optional<Session>> findValid(UUID uuid, String ip, AccountType expectedAccountType) {
        if (!settings.sessions().enabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String resolvedIp = settings.sessions().bindToIp() ? ip : null;
        return storage.findValidSession(uuid, resolvedIp, expectedAccountType);
    }

    @Override
    public CompletableFuture<Session> create(UUID uuid, String ip, AccountType accountType) {
        Instant expiresAt = settings.sessions().ttlMinutes() <= 0
                ? Instant.now().plus(Duration.ofDays(3650))
                : Instant.now().plus(Duration.ofMinutes(settings.sessions().ttlMinutes()));
        Session session = new Session(generateToken(), ip, uuid, accountType, expiresAt);
        if (!settings.sessions().enabled()) {
            return CompletableFuture.completedFuture(session);
        }
        return storage.saveSession(session).thenApply(ignored -> session);
    }

    @Override
    public CompletableFuture<Void> invalidate(UUID uuid) {
        return storage.deleteSessions(uuid);
    }

    @Override
    public CompletableFuture<Void> invalidate(String token) {
        return storage.deleteSession(token);
    }

    @Override
    public CompletableFuture<Integer> cleanupExpired() {
        return storage.deleteExpiredSessions();
    }

    @Override
    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
}
