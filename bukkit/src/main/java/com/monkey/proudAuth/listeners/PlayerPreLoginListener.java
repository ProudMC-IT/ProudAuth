package com.monkey.proudAuth.listeners;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerPreLoginListener implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final PremiumVerifier premiumVerifier;
    private final ProxyBridgeService bridgeService;
    private final BruteForceGuard bruteForceGuard;
    private final Map<UUID, ResolvedLogin> resolvedLogins;

    public PlayerPreLoginListener(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            PremiumVerifier premiumVerifier,
            ProxyBridgeService bridgeService,
            BruteForceGuard bruteForceGuard
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.premiumVerifier = premiumVerifier;
        this.bridgeService = bridgeService;
        this.bruteForceGuard = bruteForceGuard;
        this.resolvedLogins = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        String ip = event.getAddress() == null ? "unknown" : event.getAddress().getHostAddress();
        debug("PreLogin start player=%s ip=%s uuid=%s proxyMode=%s",
                event.getName(),
                ip,
                event.getUniqueId(),
                pluginConfig.settings().proxy().mode());
        BruteForceGuard.LockStatus lockStatus = bruteForceGuard.check(ip).join();
        debug("PreLogin lock check player=%s ip=%s locked=%s remaining=%s",
                event.getName(),
                ip,
                lockStatus.locked(),
                lockStatus.remainingSeconds());
        if (lockStatus.locked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, langConfig.message("kick-ip-banned"));
            return;
        }

        ProudAuthSettings.ProxyMode proxyMode = pluginConfig.settings().proxy().mode();
        ResolvedLogin resolvedLogin;

        if (proxyMode == ProudAuthSettings.ProxyMode.VELOCITY) {
            ProxyBridgeService.VerificationResult bridgeResult = bridgeService.consumeAndVerify(event.getName(), event.getUniqueId(), ip).join();
            debug("PreLogin bridge result player=%s status=%s accepted=%s",
                    event.getName(),
                    bridgeResult.status(),
                    bridgeResult.accepted());
            if (bridgeResult.accepted()) {
                resolvedLogin = applyBridgeAssertion(event, bridgeResult.assertion().orElseThrow());
            } else if (pluginConfig.settings().bridge().enabled()
                    && pluginConfig.settings().bridge().mode() == ProudAuthSettings.BridgeMode.STRICT) {
                debug("PreLogin denied by STRICT bridge player=%s uuid=%s", event.getName(), event.getUniqueId());
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, langConfig.message("kick-bridge-required"));
                return;
            } else {
                resolvedLogin = resolveVelocityFallback(event, ip);
            }
        } else {
            PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
            debug("PreLogin standalone premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                    event.getName(),
                    premiumCheck.premium(),
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName());
            UUID resolvedUuid = premiumCheck.premium()
                    ? premiumCheck.resolvedUuid()
                    : PremiumVerifier.offlineUuid(event.getName());
            String resolvedName = premiumCheck.resolvedName();
            AccountType accountType = premiumCheck.premium() ? AccountType.PREMIUM : AccountType.CRACKED;

            PlayerProfile profile = event.getPlayerProfile();
            if (profile == null) {
                profile = Bukkit.createProfileExact(resolvedUuid, resolvedName);
            } else {
                profile.setId(resolvedUuid);
                profile.setName(resolvedName);
            }
            event.setPlayerProfile(profile);
            resolvedLogin = new ResolvedLogin(resolvedUuid, resolvedName, accountType, ip);
        }

        resolvedLogins.put(resolvedLogin.uuid(), resolvedLogin);
        debug("PreLogin resolved player=%s resolvedName=%s accountType=%s storedKey=%s ip=%s",
                event.getName(),
                resolvedLogin.username(),
                resolvedLogin.accountType(),
                resolvedLogin.uuid(),
                resolvedLogin.ipAddress());
    }

    private ResolvedLogin applyBridgeAssertion(AsyncPlayerPreLoginEvent event, ProxyBridgeAssertion assertion) {
        debug("PreLogin applying bridge assertion player=%s assertionUuid=%s assertionName=%s accountType=%s ip=%s",
                event.getName(),
                assertion.uuid(),
                assertion.resolvedName(),
                assertion.accountType(),
                assertion.ipAddress());
        PlayerProfile profile = event.getPlayerProfile();
        if (profile == null) {
            profile = Bukkit.createProfileExact(assertion.uuid(), assertion.resolvedName());
            event.setPlayerProfile(profile);
        } else if (!assertion.uuid().equals(profile.getId()) || !assertion.resolvedName().equals(profile.getName())) {
            profile.setId(assertion.uuid());
            profile.setName(assertion.resolvedName());
            event.setPlayerProfile(profile);
        }
        return new ResolvedLogin(assertion.uuid(), assertion.resolvedName(), assertion.accountType(), assertion.ipAddress());
    }

    private ResolvedLogin resolveVelocityFallback(AsyncPlayerPreLoginEvent event, String ipAddress) {
        UUID currentUuid = event.getUniqueId();
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getName());
        debug("PreLogin velocity fallback player=%s currentUuid=%s offlineUuid=%s",
                event.getName(),
                currentUuid,
                offlineUuid);

        if (!currentUuid.equals(offlineUuid)) {
            debug("PreLogin velocity fallback trusted forwarded premium player=%s uuid=%s", event.getName(), currentUuid);
            return new ResolvedLogin(currentUuid, event.getName(), AccountType.PREMIUM, ipAddress);
        }

        PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifier.verify(event.getName()).join();
        debug("PreLogin velocity premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                event.getName(),
                premiumCheck.premium(),
                premiumCheck.resolvedUuid(),
                premiumCheck.resolvedName());
        if (premiumCheck.premium()) {
            PlayerProfile profile = event.getPlayerProfile();
            if (profile == null) {
                profile = Bukkit.createProfileExact(premiumCheck.resolvedUuid(), premiumCheck.resolvedName());
            } else {
                profile.setId(premiumCheck.resolvedUuid());
                profile.setName(premiumCheck.resolvedName());
            }
            event.setPlayerProfile(profile);
            return new ResolvedLogin(
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName(),
                    AccountType.PREMIUM,
                    ipAddress
            );
        }

        return new ResolvedLogin(currentUuid, event.getName(), AccountType.CRACKED, ipAddress);
    }

    public Optional<ResolvedLogin> consume(UUID uuid) {
        return Optional.ofNullable(resolvedLogins.remove(uuid));
    }

    private void debug(String template, Object... args) {
        if (!pluginConfig.debugEnabled()) {
            return;
        }
        plugin.getLogger().info("[DEBUG] " + template.formatted(args));
    }

    public record ResolvedLogin(UUID uuid, String username, AccountType accountType, String ipAddress) {
    }
}
