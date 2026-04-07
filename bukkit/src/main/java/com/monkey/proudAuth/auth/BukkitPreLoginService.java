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

/**
 * Resolves the backend login identity before the player fully joins.
 */
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
        debug(DebugChannel.PLAYER_RESOLUTION, "PreLogin start player=%s ip=%s uuid=%s proxyMode=%s",
                event.getName(),
                ip,
                event.getUniqueId(),
                pluginConfig.settings().proxy().mode());

        BruteForceGuard.LockStatus lockStatus = bruteForceGuard.check(ip).join();
        debug(DebugChannel.SECURITY_FLOW, "PreLogin lock check player=%s ip=%s locked=%s remaining=%s",
                event.getName(),
                ip,
                lockStatus.locked(),
                lockStatus.remainingSeconds());
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

        debug(DebugChannel.PLAYER_RESOLUTION, "PreLogin resolved player=%s resolvedName=%s accountType=%s storedKey=%s ip=%s",
                event.getName(),
                resolvedLogin.username(),
                resolvedLogin.accountType(),
                resolvedLogin.uuid(),
                resolvedLogin.ipAddress());
        return Optional.of(resolvedLogin);
    }

    private Optional<ResolvedLogin> resolveForVelocity(AsyncPlayerPreLoginEvent event, String ip) {
        ProxyBridgeService.VerificationResult bridgeResult = bridgeService.consumeAndVerify(event.getName(), event.getUniqueId(), ip).join();
        debug(DebugChannel.BRIDGE_FLOW, "PreLogin bridge result player=%s status=%s accepted=%s",
                event.getName(),
                bridgeResult.status(),
                bridgeResult.accepted());

        if (bridgeResult.accepted()) {
            return Optional.of(applyBridgeAssertion(event, bridgeResult.assertion().orElseThrow()));
        }

        if (pluginConfig.settings().bridge().enabled()
                && pluginConfig.settings().bridge().mode() == ProudAuthSettings.BridgeMode.STRICT) {
            debug(DebugChannel.BRIDGE_FLOW, "PreLogin denied by STRICT bridge player=%s uuid=%s", event.getName(), event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-bridge-required"));
            return Optional.empty();
        }

        return Optional.of(resolveVelocityFallback(event, ip));
    }

    private ResolvedLogin resolveStandalone(AsyncPlayerPreLoginEvent event, String ip) {
        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debug(DebugChannel.PREMIUM_FLOW, "PreLogin standalone premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                event.getName(),
                premiumCheck.premium(),
                premiumCheck.resolvedUuid(),
                premiumCheck.resolvedName());

        UUID resolvedUuid = premiumCheck.premium()
                ? premiumCheck.resolvedUuid()
                : PremiumVerifier.offlineUuid(event.getName());
        String resolvedName = premiumCheck.resolvedName();
        AccountType accountType = premiumCheck.premium() ? AccountType.PREMIUM : AccountType.CRACKED;

        applyProfile(event, resolvedUuid, resolvedName);
        return new ResolvedLogin(resolvedUuid, resolvedName, accountType, ip);
    }

    private ResolvedLogin applyBridgeAssertion(AsyncPlayerPreLoginEvent event, ProxyBridgeAssertion assertion) {
        debug(DebugChannel.BRIDGE_FLOW, "PreLogin applying bridge assertion player=%s assertionUuid=%s assertionName=%s accountType=%s ip=%s",
                event.getName(),
                assertion.uuid(),
                assertion.resolvedName(),
                assertion.accountType(),
                assertion.ipAddress());
        debug(DebugChannel.PROFILE_FLOW, "PreLogin bridge assertion accepted without backend profile mutation player=%s currentUuid=%s assertedUuid=%s",
                event.getName(),
                event.getUniqueId(),
                assertion.uuid());
        return new ResolvedLogin(assertion.uuid(), assertion.resolvedName(), assertion.accountType(), assertion.ipAddress());
    }

    private ResolvedLogin resolveVelocityFallback(AsyncPlayerPreLoginEvent event, String ipAddress) {
        UUID currentUuid = event.getUniqueId();
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getName());
        debug(DebugChannel.PROFILE_FLOW, "PreLogin velocity fallback player=%s currentUuid=%s offlineUuid=%s",
                event.getName(),
                currentUuid,
                offlineUuid);

        if (!currentUuid.equals(offlineUuid)) {
            debug(DebugChannel.PROFILE_FLOW, "PreLogin velocity fallback trusted forwarded premium player=%s uuid=%s", event.getName(), currentUuid);
            return new ResolvedLogin(currentUuid, event.getName(), AccountType.PREMIUM, ipAddress);
        }

        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debug(DebugChannel.PREMIUM_FLOW, "PreLogin velocity premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                event.getName(),
                premiumCheck.premium(),
                premiumCheck.resolvedUuid(),
                premiumCheck.resolvedName());
        if (premiumCheck.premium()) {
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

    private void debug(DebugChannel channel, String template, Object... args) {
        logger.debug(pluginConfig.settings().debugger(), channel, template, args);
    }
}
