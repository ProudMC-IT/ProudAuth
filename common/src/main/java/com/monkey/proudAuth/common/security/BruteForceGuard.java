package com.monkey.proudAuth.common.security;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.storage.StorageProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class BruteForceGuard {

    private final StorageProvider storage;
    private final Map<String, AttemptRecord> attempts;
    private volatile ProudAuthSettings settings;

    public BruteForceGuard(StorageProvider storage, ProudAuthSettings settings) {
        this.storage = storage;
        this.settings = settings;
        this.attempts = new ConcurrentHashMap<>();
    }

    public CompletableFuture<LockStatus> check(String ip) {
        clearExpiredAttemptWindow(ip);
        return storage.findActiveBan(ip)
                .thenApply(optionalBan -> optionalBan
                        .map(ban -> new LockStatus(true, remainingSeconds(ban.expiresAt())))
                        .orElseGet(() -> new LockStatus(false, 0)));
    }

    public CompletableFuture<FailureSnapshot> recordFailure(String ip) {
        clearExpiredAttemptWindow(ip);
        AttemptRecord updated = attempts.compute(ip, (ignored, current) -> {
            Instant now = Instant.now();
            if (current == null || Duration.between(current.firstAttemptAt(), now).getSeconds() > settings.security().lockoutSeconds()) {
                return new AttemptRecord(1, now);
            }
            return new AttemptRecord(current.attempts() + 1, current.firstAttemptAt());
        });

        if (updated.attempts() < settings.security().maxAttempts()) {
            return CompletableFuture.completedFuture(new FailureSnapshot(
                    updated.attempts(),
                    settings.security().maxAttempts(),
                    false,
                    0
            ));
        }

        attempts.remove(ip);
        long lockoutSeconds = settings.security().lockoutSeconds();
        Instant expiresAt = Instant.now().plusSeconds(lockoutSeconds);
        return storage.banIp(ip, expiresAt, "Troppi tentativi falliti")
                .thenApply(ignored -> new FailureSnapshot(
                        updated.attempts(),
                        settings.security().maxAttempts(),
                        true,
                        lockoutSeconds
                ));
    }

    public void clearFailures(String ip) {
        attempts.remove(ip);
    }

    public CompletableFuture<Void> banIp(String ip, long seconds, String reason) {
        return storage.banIp(ip, Instant.now().plusSeconds(seconds), reason);
    }

    public CompletableFuture<Void> unbanIp(String ip) {
        return storage.unbanIp(ip);
    }

    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
        attempts.clear();
    }

    private void clearExpiredAttemptWindow(String ip) {
        AttemptRecord current = attempts.get(ip);
        if (current == null) {
            return;
        }
        if (Duration.between(current.firstAttemptAt(), Instant.now()).getSeconds() > settings.security().lockoutSeconds()) {
            attempts.remove(ip);
        }
    }

    private static long remainingSeconds(Instant expiresAt) {
        return Math.max(1, Duration.between(Instant.now(), expiresAt).getSeconds());
    }

    private record AttemptRecord(int attempts, Instant firstAttemptAt) {
    }

    public record LockStatus(boolean locked, long remainingSeconds) {
    }

    public record FailureSnapshot(int attempts, int maxAttempts, boolean locked, long remainingSeconds) {
    }
}
