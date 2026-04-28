package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;
import java.util.UUID;

public final class BukkitPreLoginService {

    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final PremiumVerifier premiumVerifier;
    private final ProxyBridgeService bridgeService;
    private final BruteForceGuard bruteForceGuard;
    private final StorageProvider storage;
    private final IdentityClaimService identityClaimService;
    private final ProudAuthConsoleLogger logger;

    public BukkitPreLoginService(
            PluginConfig pluginConfig,
            LangConfig langConfig,
            PremiumVerifier premiumVerifier,
            ProxyBridgeService bridgeService,
            BruteForceGuard bruteForceGuard,
            StorageProvider storage,
            IdentityClaimService identityClaimService,
            ProudAuthConsoleLogger logger
    ) {
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.premiumVerifier = premiumVerifier;
        this.bridgeService = bridgeService;
        this.bruteForceGuard = bruteForceGuard;
        this.storage = storage;
        this.identityClaimService = identityClaimService;
        this.logger = logger;
    }

    public Optional<ResolvedLogin> resolve(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress() == null ? "unknown" : event.getAddress().getHostAddress();
        debugEvent(DebugChannel.PLAYER_RESOLUTION, "prelogin_start",
                "player", event.getName(),
                "ip", ip,
                "uuid", event.getUniqueId(),
                "proxy_mode", pluginConfig.settings().proxy().mode());

        BruteForceGuard.LockStatus lockStatus = bruteForceGuard.check(ip).join();
        debugEvent(DebugChannel.SECURITY_FLOW, "ip_lock_check",
                "player", event.getName(),
                "ip", ip,
                "locked", lockStatus.locked(),
                "remaining_seconds", lockStatus.remainingSeconds());
        if (lockStatus.locked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, langConfig.message("kick-ip-banned"));
            return Optional.empty();
        }

        Optional<String> trustedIp = storage.findTrustedIp(event.getName()).join();
        if (trustedIp.isPresent() && !trustedIp.get().equalsIgnoreCase(ip)) {
            debugEvent(DebugChannel.SECURITY_FLOW, "trusted_ip_mismatch",
                    "player", event.getName(),
                    "ip", ip,
                    "trusted_ip", trustedIp.get());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-trusted-ip-mismatch"));
            return Optional.empty();
        }

        boolean inWhitelist = storage.isInPremiumIpWhitelist(event.getName()).join();
        if (inWhitelist) {
            boolean ipAllowed = storage.isPremiumIpWhitelisted(event.getName(), ip).join();
            if (!ipAllowed) {
                debugEvent(DebugChannel.SECURITY_FLOW, "premium_whitelist_ip_mismatch",
                        "player", event.getName(),
                        "ip", ip);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-whitelist-ip-required"));
                return Optional.empty();
            }

            PremiumVerifier.PremiumCheckResult whitelistCheck = premiumVerifier.verify(event.getName()).join();
            if (!whitelistCheck.premium()) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "premium_whitelist_non_premium_denied",
                        "player", event.getName(),
                        "ip", ip);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-whitelist-premium-required"));
                return Optional.empty();
            }
        }

        Optional<ResolvedLogin> resolvedLogin = pluginConfig.settings().proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY
                ? resolveForVelocity(event, ip)
                : resolveStandalone(event, ip);
        if (resolvedLogin.isEmpty()) {
            return Optional.empty();
        }
        ResolvedLogin login = resolvedLogin.get();

        debugEvent(DebugChannel.PLAYER_RESOLUTION, "prelogin_resolved",
                "player", event.getName(),
                "resolved_name", login.username(),
                "account_type", login.accountType(),
                "stored_key", login.uuid(),
                "ip", login.ipAddress());
        return resolvedLogin;
    }

    private Optional<ResolvedLogin> resolveForVelocity(AsyncPlayerPreLoginEvent event, String ip) {
        ProxyBridgeService.VerificationResult bridgeResult = bridgeService.consumeAndVerify(event.getName(), event.getUniqueId(), ip).join();
        debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_verification",
                "player", event.getName(),
                "status", bridgeResult.status(),
                "accepted", bridgeResult.accepted());

        if (bridgeResult.accepted()) {
            ProxyBridgeAssertion assertion = bridgeResult.assertion().orElseThrow();
            if (assertion.accountType() == AccountType.CRACKED
                    && shouldDenyPremiumImpersonation(event.getName(), assertion.uuid(), ip)) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "bridge_impersonation_denied",
                        "player", event.getName(),
                        "assertion_uuid", assertion.uuid(),
                        "ip", ip);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-premium-impersonation"));
                return Optional.empty();
            }
            return Optional.of(applyBridgeAssertion(event, assertion));
        }

        if (pluginConfig.settings().bridge().enabled()
                && pluginConfig.settings().bridge().mode() == ProudAuthSettings.BridgeMode.STRICT) {
            debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_strict_denied",
                    "player", event.getName(),
                    "uuid", event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-bridge-required"));
            return Optional.empty();
        }

        return resolveVelocityFallback(event, ip);
    }

    private Optional<ResolvedLogin> resolveStandalone(AsyncPlayerPreLoginEvent event, String ip) {
        UUID currentUuid = event.getUniqueId();
        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debugEvent(DebugChannel.PREMIUM_FLOW, "premium_check_standalone",
                "player", event.getName(),
                "premium", premiumCheck.premium(),
                "resolved_uuid", premiumCheck.resolvedUuid(),
                "resolved_name", premiumCheck.resolvedName());

        if (!premiumCheck.premium()) {
            Optional<com.monkey.proudAuth.common.storage.AccountRecord> dbAccount = storage.findAccountByUsername(event.getName()).join();
            if (dbAccount.isPresent() && dbAccount.get().accountType() == AccountType.PREMIUM) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "standalone_premium_db_mismatch",
                        "player", event.getName(),
                        "stored_uuid", dbAccount.get().uuid(),
                        "stored_name", dbAccount.get().username());
            }
            return Optional.of(new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ip));
        }

        UUID mojangUuid = premiumCheck.resolvedUuid();
        boolean currentUuidProvesPremium = mojangUuid.equals(currentUuid);
        boolean trustedIpMatch = hasTrustedIpMatch(event.getName(), ip);
        if (!currentUuidProvesPremium && !trustedIpMatch) {
            debugEvent(DebugChannel.PREMIUM_FLOW, "standalone_premium_impersonation_denied",
                    "player", event.getName(),
                    "current_uuid", currentUuid,
                    "resolved_uuid", mojangUuid,
                    "ip", ip);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-premium-impersonation"));
            return Optional.empty();
        }

        applyProfile(event, mojangUuid, premiumCheck.resolvedName());
        return Optional.of(new ResolvedLogin(mojangUuid, premiumCheck.resolvedName(), AccountType.PREMIUM, ip));
    }

    private ResolvedLogin applyBridgeAssertion(AsyncPlayerPreLoginEvent event, ProxyBridgeAssertion assertion) {
        debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_assertion_apply",
                "player", event.getName(),
                "assertion_uuid", assertion.uuid(),
                "assertion_name", assertion.resolvedName(),
                "account_type", assertion.accountType(),
                "ip", assertion.ipAddress());
        debugEvent(DebugChannel.PROFILE_FLOW, "bridge_assertion_profile_accept",
                "player", event.getName(),
                "current_uuid", event.getUniqueId(),
                "asserted_uuid", assertion.uuid());
        return new ResolvedLogin(assertion.uuid(), assertion.resolvedName(), assertion.accountType(), assertion.ipAddress());
    }

    private Optional<ResolvedLogin> resolveVelocityFallback(AsyncPlayerPreLoginEvent event, String ipAddress) {
        UUID currentUuid = event.getUniqueId();
        debugEvent(DebugChannel.PROFILE_FLOW, "velocity_fallback_start",
                "player", event.getName(),
                "current_uuid", currentUuid);

        if (identityClaimService.isClaimOnFirstJoinEnabled()) {
            Optional<AccountType> effectiveClaim = identityClaimService.resolveEffectiveClaim(event.getName()).join();
            if (effectiveClaim.isEmpty()) {
                return Optional.of(new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress));
            }
            if (effectiveClaim.get() == AccountType.CRACKED) {
                return Optional.of(new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress));
            }
        }

        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debugEvent(DebugChannel.PREMIUM_FLOW, "velocity_fallback_premium_check",
                "player", event.getName(),
                "premium", premiumCheck.premium(),
                "resolved_uuid", premiumCheck.resolvedUuid(),
                "resolved_name", premiumCheck.resolvedName());
        if (premiumCheck.premium()) {
            UUID mojangUuid = premiumCheck.resolvedUuid();
            boolean currentUuidProvesPremium = mojangUuid.equals(currentUuid);
            boolean trustedIpMatch = hasTrustedIpMatch(event.getName(), ipAddress);
            if (!trustedIpMatch && !currentUuidProvesPremium) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "velocity_fallback_premium_impersonation_denied",
                        "player", event.getName(),
                        "current_uuid", currentUuid,
                        "resolved_uuid", mojangUuid,
                        "ip", ipAddress);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-premium-impersonation"));
                return Optional.empty();
            }
            if (trustedIpMatch && !currentUuidProvesPremium) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "velocity_fallback_premium_trusted_ip_override",
                        "player", event.getName(),
                        "ip", ipAddress);
            }
            return Optional.of(new ResolvedLogin(
                    mojangUuid,
                    premiumCheck.resolvedName(),
                    AccountType.PREMIUM,
                    ipAddress
            ));
        }

        if (identityClaimService.isClaimOnFirstJoinEnabled()
                && identityClaimService.resolveEffectiveClaim(event.getName()).join().orElse(AccountType.CRACKED) == AccountType.PREMIUM) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-premium-impersonation"));
            return Optional.empty();
        }

        return Optional.of(new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress));
    }

    private void applyProfile(AsyncPlayerPreLoginEvent event, UUID uuid, String name) {
        event.setPlayerProfile(Bukkit.createProfileExact(uuid, name));
    }

    private boolean hasTrustedIpMatch(String username, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return false;
        }
        return storage.findTrustedIp(username)
                .thenApply(trustedIp -> trustedIp.map(ipAddress::equalsIgnoreCase).orElse(false))
                .exceptionally(ignored -> false)
                .join();
    }

    private boolean shouldDenyPremiumImpersonation(String username, UUID currentUuid, String ipAddress) {
        if (!pluginConfig.settings().premium().enabled()) {
            return false;
        }
        if (identityClaimService.isClaimOnFirstJoinEnabled()) {
            Optional<AccountType> effectiveClaim = identityClaimService.resolveEffectiveClaim(username).join();
            if (effectiveClaim.isEmpty()) {
                return false;
            }
            return effectiveClaim.get() == AccountType.PREMIUM;
        }
        if (hasTrustedIpMatch(username, ipAddress)) {
            return false;
        }
        try {
            PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(username).join();
            if (!premiumCheck.premium()) {
                return false;
            }
            return currentUuid == null || !currentUuid.equals(premiumCheck.resolvedUuid());
        } catch (Exception ignored) {
            return false;
        }
    }

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), channel, eventName, keyValues);
    }
}
