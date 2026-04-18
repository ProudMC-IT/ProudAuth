package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
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
    private final ProudAuthConsoleLogger logger;

    public BukkitPreLoginService(
            PluginConfig pluginConfig,
            LangConfig langConfig,
            PremiumVerifier premiumVerifier,
            ProxyBridgeService bridgeService,
            BruteForceGuard bruteForceGuard,
            ProudAuthConsoleLogger logger
    ) {
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.premiumVerifier = premiumVerifier;
        this.bridgeService = bridgeService;
        this.bruteForceGuard = bruteForceGuard;
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

        ResolvedLogin resolvedLogin = pluginConfig.settings().proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY
                ? resolveForVelocity(event, ip).orElse(null)
                : resolveStandalone(event, ip);
        if (resolvedLogin == null) {
            return Optional.empty();
        }

        debugEvent(DebugChannel.PLAYER_RESOLUTION, "prelogin_resolved",
                "player", event.getName(),
                "resolved_name", resolvedLogin.username(),
                "account_type", resolvedLogin.accountType(),
                "stored_key", resolvedLogin.uuid(),
                "ip", resolvedLogin.ipAddress());
        return Optional.of(resolvedLogin);
    }

    private Optional<ResolvedLogin> resolveForVelocity(AsyncPlayerPreLoginEvent event, String ip) {
        ProxyBridgeService.VerificationResult bridgeResult = bridgeService.consumeAndVerify(event.getName(), event.getUniqueId(), ip).join();
        debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_verification",
                "player", event.getName(),
                "status", bridgeResult.status(),
                "accepted", bridgeResult.accepted());

        if (bridgeResult.accepted()) {
            return Optional.of(applyBridgeAssertion(event, bridgeResult.assertion().orElseThrow()));
        }

        if (pluginConfig.settings().bridge().enabled()
                && pluginConfig.settings().bridge().mode() == ProudAuthSettings.BridgeMode.STRICT) {
            debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_strict_denied",
                    "player", event.getName(),
                    "uuid", event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-bridge-required"));
            return Optional.empty();
        }

        return Optional.of(resolveVelocityFallback(event, ip));
    }

    private ResolvedLogin resolveStandalone(AsyncPlayerPreLoginEvent event, String ip) {
        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debugEvent(DebugChannel.PREMIUM_FLOW, "premium_check_standalone",
                "player", event.getName(),
                "premium", premiumCheck.premium(),
                "resolved_uuid", premiumCheck.resolvedUuid(),
                "resolved_name", premiumCheck.resolvedName());

        UUID resolvedUuid = premiumCheck.premium()
                ? premiumCheck.resolvedUuid()
                : PremiumVerifier.offlineUuid(event.getName());
        String resolvedName = premiumCheck.resolvedName();
        AccountType accountType = premiumCheck.premium() ? AccountType.PREMIUM : AccountType.CRACKED;

        applyProfile(event, resolvedUuid, resolvedName);
        return new ResolvedLogin(resolvedUuid, resolvedName, accountType, ip);
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

    private ResolvedLogin resolveVelocityFallback(AsyncPlayerPreLoginEvent event, String ipAddress) {
        UUID currentUuid = event.getUniqueId();
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getName());
        debugEvent(DebugChannel.PROFILE_FLOW, "velocity_fallback_start",
                "player", event.getName(),
                "current_uuid", currentUuid,
                "offline_uuid", offlineUuid);

        if (!currentUuid.equals(offlineUuid)) {
            debugEvent(DebugChannel.PROFILE_FLOW, "velocity_fallback_forwarded_premium",
                    "player", event.getName(),
                    "uuid", currentUuid);
            return new ResolvedLogin(currentUuid, event.getName(), AccountType.PREMIUM, ipAddress);
        }

        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debugEvent(DebugChannel.PREMIUM_FLOW, "velocity_fallback_premium_check",
                "player", event.getName(),
                "premium", premiumCheck.premium(),
                "resolved_uuid", premiumCheck.resolvedUuid(),
                "resolved_name", premiumCheck.resolvedName());
        if (premiumCheck.premium()) {
            if (pluginConfig.settings().premium().requireUuidProof()) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "velocity_fallback_premium_downgraded",
                        "player", event.getName(),
                        "reason", "UUID_PROOF_REQUIRED",
                        "current_uuid", currentUuid);
                return new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress);
            }
            applyProfile(event, premiumCheck.resolvedUuid(), premiumCheck.resolvedName());
            return new ResolvedLogin(
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName(),
                    AccountType.PREMIUM,
                    ipAddress
            );
        }

        return new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress);
    }

    private void applyProfile(AsyncPlayerPreLoginEvent event, UUID uuid, String name) {
        event.setPlayerProfile(Bukkit.createProfileExact(uuid, name));
    }

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), channel, eventName, keyValues);
    }
}
