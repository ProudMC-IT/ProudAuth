package com.monkey.proudAuth.velocity.security;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.storage.StorageProvider;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

public final class VelocityNetworkGuardService {

    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<ProudAuthNetworkConfig.Guards> guardsSupplier;
    private final Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;
    private final Map<String, Deque<Long>> inMemoryAttempts = new ConcurrentHashMap<>();

    public VelocityNetworkGuardService(
            Supplier<StorageProvider> storageSupplier,
            Supplier<ProudAuthNetworkConfig.Guards> guardsSupplier,
            Supplier<com.monkey.proudAuth.common.config.ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.guardsSupplier = guardsSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    public GuardDecision evaluate(String username, String ipAddress) {
        ProudAuthNetworkConfig.Guards guards = guardsSupplier.get();
        if (!guards.enabled()) {
            return GuardDecision.permit();
        }

        String canonicalUsername = canonicalUsername(username);
        if (isRateLimited(ipAddress, guards)) {
            applyBan(ipAddress, guards.antiBotBanSeconds(), "AntiBot rate limit");
            debugEvent("guard_block_antibot",
                    "username", canonicalUsername,
                    "ip", ipAddress,
                    "ban_seconds", guards.antiBotBanSeconds());
            return GuardDecision.blocked(GuardReason.ANTIBOT_RATE_LIMIT);
        }

        try {
            Instant now = Instant.now();
            storageSupplier.get().saveIpHistory(canonicalUsername, ipAddress, AccountType.CRACKED, now).join();

            Instant since = now.minus(guards.identityWindowSeconds(), ChronoUnit.SECONDS);
            int usernamesForIp = storageSupplier.get().countDistinctUsernamesForIpSince(ipAddress, since).join();
            if (usernamesForIp > guards.identityMaxUsernamesPerIp()) {
                applyBan(ipAddress, guards.identityBanSeconds(), "Troppi nickname sullo stesso IP");
                debugEvent("guard_block_usernames_per_ip",
                        "username", canonicalUsername,
                        "ip", ipAddress,
                        "distinct", usernamesForIp,
                        "threshold", guards.identityMaxUsernamesPerIp(),
                        "ban_seconds", guards.identityBanSeconds());
                return GuardDecision.blocked(GuardReason.TOO_MANY_USERNAMES_PER_IP);
            }

            int ipsForUsername = storageSupplier.get().countDistinctIpsForUsernameSince(canonicalUsername, since).join();
            if (ipsForUsername > guards.identityMaxIpsPerUsername()) {
                applyBan(ipAddress, guards.identityBanSeconds(), "Troppe origini IP sullo stesso nickname");
                debugEvent("guard_block_ips_per_username",
                        "username", canonicalUsername,
                        "ip", ipAddress,
                        "distinct", ipsForUsername,
                        "threshold", guards.identityMaxIpsPerUsername(),
                        "ban_seconds", guards.identityBanSeconds());
                return GuardDecision.blocked(GuardReason.TOO_MANY_IPS_PER_USERNAME);
            }
        } catch (Exception exception) {
            debugEvent("guard_storage_error",
                    "username", canonicalUsername,
                    "ip", ipAddress,
                    "error", exception.getMessage());
            return GuardDecision.blocked(GuardReason.STORAGE_ERROR);
        }

        return GuardDecision.permit();
    }

    public void recordResolvedProfile(String username, String ipAddress, AccountType accountType) {
        ProudAuthNetworkConfig.Guards guards = guardsSupplier.get();
        if (!guards.enabled()) {
            return;
        }
        try {
            storageSupplier.get()
                    .saveIpHistory(canonicalUsername(username), ipAddress, accountType, Instant.now())
                    .join();
        } catch (Exception exception) {
            debugEvent("guard_profile_record_error",
                    "username", canonicalUsername(username),
                    "ip", ipAddress,
                    "error", exception.getMessage());
        }
    }

    public PremiumPromotionDecision evaluatePremiumAutoPromotion(
            String username,
            String ipAddress,
            int maxUsernamesPerIp1h,
            int maxIpsPerUsername24h,
            boolean denyWhenIpBanned
    ) {
        String canonicalUsername = canonicalUsername(username);
        try {
            StorageProvider storage = storageSupplier.get();
            Instant now = Instant.now();
            int usernamesForIp1h = storage.countDistinctUsernamesForIpSince(ipAddress, now.minus(Duration.ofHours(1))).join();
            int ipsForUsername24h = storage.countDistinctIpsForUsernameSince(canonicalUsername, now.minus(Duration.ofHours(24))).join();
            boolean ipBanned = denyWhenIpBanned && storage.findActiveBan(ipAddress).join().isPresent();

            String reason;
            boolean allowed;
            if (ipBanned) {
                allowed = false;
                reason = "IP_BANNED";
            } else if (usernamesForIp1h > maxUsernamesPerIp1h) {
                allowed = false;
                reason = "IP_USERNAME_SPREAD";
            } else if (ipsForUsername24h > maxIpsPerUsername24h) {
                allowed = false;
                reason = "USERNAME_IP_SPREAD";
            } else {
                allowed = true;
                reason = "LOW_RISK";
            }

            debugEvent("guard_premium_auto_promotion",
                    "username", canonicalUsername,
                    "ip", ipAddress,
                    "allowed", allowed,
                    "reason", reason,
                    "usernames_ip_1h", usernamesForIp1h,
                    "ips_username_24h", ipsForUsername24h,
                    "ip_banned", ipBanned,
                    "max_usernames_ip_1h", maxUsernamesPerIp1h,
                    "max_ips_username_24h", maxIpsPerUsername24h);
            return new PremiumPromotionDecision(allowed, reason, usernamesForIp1h, ipsForUsername24h, ipBanned);
        } catch (Exception exception) {
            debugEvent("guard_premium_auto_promotion_error",
                    "username", canonicalUsername,
                    "ip", ipAddress,
                    "error", exception.getMessage());
            return new PremiumPromotionDecision(false, "STORAGE_ERROR", -1, -1, false);
        }
    }

    public void cleanupHistory() {
        ProudAuthNetworkConfig.Guards guards = guardsSupplier.get();
        if (!guards.enabled()) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(guards.historyRetentionDays(), ChronoUnit.DAYS);
            int deleted = storageSupplier.get().deleteIpHistoryOlderThan(cutoff).join();
            if (deleted > 0) {
                debugEvent("guard_history_cleanup",
                        "deleted", deleted,
                        "retention_days", guards.historyRetentionDays());
            }
        } catch (Exception exception) {
            debugEvent("guard_history_cleanup_error",
                    "error", exception.getMessage());
        }
    }

    private boolean isRateLimited(String ipAddress, ProudAuthNetworkConfig.Guards guards) {
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1L, guards.antiBotWindowSeconds()) * 1000L;
        long minTimestamp = now - windowMs;

        Deque<Long> attempts = inMemoryAttempts.computeIfAbsent(ipAddress, ignored -> new ConcurrentLinkedDeque<>());
        synchronized (attempts) {
            while (!attempts.isEmpty()) {
                Long ts = attempts.peekFirst();
                if (ts == null || ts >= minTimestamp) {
                    break;
                }
                attempts.pollFirst();
            }
            attempts.addLast(now);
            return attempts.size() > guards.antiBotMaxConnectionsPerIp();
        }
    }

    private void applyBan(String ipAddress, long seconds, String reason) {
        storageSupplier.get()
                .banIp(ipAddress, Instant.now().plusSeconds(Math.max(1L, seconds)), reason)
                .join();
    }

    private static String canonicalUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.SECURITY_FLOW, eventName, keyValues);
    }

    public enum GuardReason {
        NONE,
        ANTIBOT_RATE_LIMIT,
        TOO_MANY_USERNAMES_PER_IP,
        TOO_MANY_IPS_PER_USERNAME,
        STORAGE_ERROR
    }

    public record GuardDecision(boolean allowed, GuardReason reason) {
        public static GuardDecision permit() {
            return new GuardDecision(true, GuardReason.NONE);
        }

        public static GuardDecision blocked(GuardReason reason) {
            return new GuardDecision(false, reason);
        }
    }

    public record PremiumPromotionDecision(
            boolean allowed,
            String reason,
            int distinctUsernamesForIp1h,
            int distinctIpsForUsername24h,
            boolean ipBanned
    ) {
    }
}
