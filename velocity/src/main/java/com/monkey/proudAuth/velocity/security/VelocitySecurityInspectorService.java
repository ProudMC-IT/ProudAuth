package com.monkey.proudAuth.velocity.security;

import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.IpHistoryStorage;
import com.monkey.proudAuth.common.storage.StorageProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class VelocitySecurityInspectorService {

    private final Supplier<StorageProvider> storageSupplier;

    public VelocitySecurityInspectorService(Supplier<StorageProvider> storageSupplier) {
        this.storageSupplier = storageSupplier;
    }

    public CompletableFuture<IpHistoryReport> inspectIp(String ipAddress) {
        StorageProvider storage = storageSupplier.get();
        Instant now = Instant.now();
        CompletableFuture<Integer> usernames1h = storage.countDistinctUsernamesForIpSince(ipAddress, now.minus(Duration.ofHours(1)));
        CompletableFuture<Integer> usernames24h = storage.countDistinctUsernamesForIpSince(ipAddress, now.minus(Duration.ofHours(24)));
        CompletableFuture<Integer> usernames7d = storage.countDistinctUsernamesForIpSince(ipAddress, now.minus(Duration.ofDays(7)));
        CompletableFuture<Optional<com.monkey.proudAuth.common.storage.IpBanRecord>> activeBan = storage.findActiveBan(ipAddress);

        return CompletableFuture.allOf(usernames1h, usernames24h, usernames7d, activeBan)
                .thenApply(ignored -> {
                    Optional<com.monkey.proudAuth.common.storage.IpBanRecord> ban = activeBan.join();
                    long banRemainingSeconds = ban
                            .map(record -> Math.max(0L, Duration.between(Instant.now(), record.expiresAt()).getSeconds()))
                            .orElse(0L);
                    String banReason = ban.map(record -> record.reason() == null ? "n/a" : record.reason()).orElse("n/a");
                    return new IpHistoryReport(
                            ipAddress,
                            usernames1h.join(),
                            usernames24h.join(),
                            usernames7d.join(),
                            ban.isPresent(),
                            banRemainingSeconds,
                            banReason
                    );
                });
    }

    public CompletableFuture<UserRiskReport> inspectUser(String username) {
        String normalizedUsername = username.toLowerCase(java.util.Locale.ROOT);
        StorageProvider storage = storageSupplier.get();
        Instant now = Instant.now();

        CompletableFuture<Integer> ips1h = storage.countDistinctIpsForUsernameSince(normalizedUsername, now.minus(Duration.ofHours(1)));
        CompletableFuture<Integer> ips24h = storage.countDistinctIpsForUsernameSince(normalizedUsername, now.minus(Duration.ofHours(24)));
        CompletableFuture<Integer> ips7d = storage.countDistinctIpsForUsernameSince(normalizedUsername, now.minus(Duration.ofDays(7)));
        CompletableFuture<Optional<AccountRecord>> account = storage.findAccountByUsername(normalizedUsername);

        return CompletableFuture.allOf(ips1h, ips24h, ips7d, account)
                .thenApply(ignored -> {
                    Optional<AccountRecord> accountRecord = account.join();
                    UUID offlineUuid = PremiumVerifier.offlineUuid(normalizedUsername);
                    boolean premiumUuidMismatch = accountRecord
                            .filter(record -> record.accountType() == AccountType.PREMIUM)
                            .map(record -> record.uuid().equals(offlineUuid))
                            .orElse(false);

                    int riskScore = calculateRiskScore(
                            ips1h.join(),
                            ips24h.join(),
                            ips7d.join(),
                            premiumUuidMismatch
                    );

                    return new UserRiskReport(
                            normalizedUsername,
                            ips1h.join(),
                            ips24h.join(),
                            ips7d.join(),
                            accountRecord.isPresent(),
                            accountRecord.map(AccountRecord::accountType).orElse(null),
                            accountRecord.map(AccountRecord::uuid).orElse(null),
                            accountRecord.map(AccountRecord::lastIp).orElse("n/a"),
                            premiumUuidMismatch,
                            riskScore,
                            classifyRisk(riskScore)
                    );
                });
    }

    public CompletableFuture<TopRiskReport> inspectTopRisks(Duration window, int limit) {
        StorageProvider storage = storageSupplier.get();
        Instant since = Instant.now().minus(window);
        int boundedLimit = Math.max(1, limit);

        CompletableFuture<List<IpHistoryStorage.IpSummary>> topIps =
                storage.topIpsByDistinctUsernamesSince(since, boundedLimit);
        CompletableFuture<List<IpHistoryStorage.UserSummary>> topUsers =
                storage.topUsersByDistinctIpsSince(since, boundedLimit);

        return CompletableFuture.allOf(topIps, topUsers)
                .thenApply(ignored -> new TopRiskReport(
                        window,
                        topIps.join(),
                        topUsers.join()
                ));
    }

    private static int calculateRiskScore(int ips1h, int ips24h, int ips7d, boolean premiumUuidMismatch) {
        int score = 0;
        if (ips1h >= 3) {
            score += 3;
        }
        if (ips24h >= 5) {
            score += 3;
        }
        if (ips7d >= 8) {
            score += 2;
        }
        if (premiumUuidMismatch) {
            score += 4;
        }
        return score;
    }

    private static RiskLevel classifyRisk(int score) {
        if (score >= 8) {
            return RiskLevel.CRITICAL;
        }
        if (score >= 5) {
            return RiskLevel.HIGH;
        }
        if (score >= 3) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    public record IpHistoryReport(
            String ipAddress,
            int distinctUsernames1h,
            int distinctUsernames24h,
            int distinctUsernames7d,
            boolean banned,
            long banRemainingSeconds,
            String banReason
    ) {
    }

    public record UserRiskReport(
            String username,
            int distinctIps1h,
            int distinctIps24h,
            int distinctIps7d,
            boolean accountExists,
            AccountType accountType,
            UUID storedUuid,
            String lastIp,
            boolean premiumUuidMismatch,
            int riskScore,
            RiskLevel riskLevel
    ) {
    }

    public record TopRiskReport(
            Duration window,
            List<IpHistoryStorage.IpSummary> topIpsByUserSpread,
            List<IpHistoryStorage.UserSummary> topUsersByIpSpread
    ) {
    }
}
