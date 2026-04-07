package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import com.monkey.proudAuth.common.session.SessionManager;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerJoinListener implements Listener {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final SessionManager sessionManager;
    private final PlayerProtection playerProtection;
    private final PlayerPreLoginListener playerPreLoginListener;

    public PlayerJoinListener(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            SessionManager sessionManager,
            PlayerProtection playerProtection,
            PlayerPreLoginListener playerPreLoginListener
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.playerProtection = playerProtection;
        this.playerPreLoginListener = playerPreLoginListener;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            java.util.Optional<PlayerPreLoginListener.ResolvedLogin> consumed = playerPreLoginListener.consume(player.getUniqueId());
            PlayerPreLoginListener.ResolvedLogin resolvedLogin = consumed
                    .orElseGet(() -> new PlayerPreLoginListener.ResolvedLogin(
                            player.getUniqueId(),
                            player.getName(),
                            player.getUniqueId().equals(PremiumVerifier.offlineUuid(player.getName())) ? AccountType.CRACKED : AccountType.PREMIUM,
                            ipAddress(player)
                    ));
            debug("Join player=%s uuid=%s preloginCacheHit=%s resolvedName=%s accountType=%s ip=%s",
                    player.getName(),
                    player.getUniqueId(),
                    consumed.isPresent(),
                    resolvedLogin.username(),
                    resolvedLogin.accountType(),
                    resolvedLogin.ipAddress());

            authService.trackPlayer(player.getUniqueId(), player.getName(), resolvedLogin.accountType());
            boolean rawBypassPermission = player.hasPermission("proudauth.bypass.auth");
            boolean bypassAuth = rawBypassPermission && resolvedLogin.accountType() != AccountType.PREMIUM;
            debug("Join bypass check player=%s uuid=%s rawBypass=%s effectiveBypass=%s accountType=%s",
                    player.getName(),
                    player.getUniqueId(),
                    rawBypassPermission,
                    bypassAuth,
                    resolvedLogin.accountType());
            if (bypassAuth) {
                debug("Join bypass branch player=%s uuid=%s", player.getName(), player.getUniqueId());
                authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.AUTHENTICATED, ipAddress(player))
                        .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (exception != null) {
                                debug("Join bypass exception player=%s uuid=%s error=%s",
                                        player.getName(),
                                        player.getUniqueId(),
                                        exception.getMessage());
                                langConfig.send(player, "error-generic");
                                return;
                            }
                            playerProtection.removeProtection(player);
                            debug("Join bypass completed player=%s uuid=%s", player.getName(), player.getUniqueId());
                        }));
                return;
            }

            boolean premiumAutoLogin = resolvedLogin.accountType() == AccountType.PREMIUM && pluginConfig.settings().premium().autoLogin();
            if (premiumAutoLogin) {
                debug("Join premium fast-path player=%s uuid=%s", player.getName(), player.getUniqueId());
                authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.PREMIUM_AUTO, resolvedLogin.ipAddress())
                        .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                return;
                            }
                            if (exception != null) {
                                debug("Join premium fast-path exception player=%s uuid=%s error=%s",
                                        player.getName(),
                                        player.getUniqueId(),
                                        exception.getMessage());
                                langConfig.send(player, "error-generic");
                                return;
                            }
                            playerProtection.removeProtection(player);
                            debug("Join premium fast-path completed player=%s uuid=%s", player.getName(), player.getUniqueId());
                            langConfig.send(player, "premium-auto-login");
                        }));
                return;
            }

            playerProtection.applyProtection(player);
            debug("Join protection applied player=%s uuid=%s", player.getName(), player.getUniqueId());

            sessionManager.findValid(player.getUniqueId(), resolvedLogin.ipAddress())
                    .thenCompose(optionalSession -> {
                        debug("Join session lookup player=%s uuid=%s sessionPresent=%s",
                                player.getName(),
                                player.getUniqueId(),
                                optionalSession.isPresent());
                        if (optionalSession.isPresent()) {
                            return authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.AUTHENTICATED, resolvedLogin.ipAddress())
                                    .thenApply(ignored -> JoinOutcome.SESSION_RESTORED);
                        }

                        debug("Join requires manual auth player=%s uuid=%s accountType=%s premiumAutoLogin=%s",
                                player.getName(),
                                player.getUniqueId(),
                                resolvedLogin.accountType(),
                                pluginConfig.settings().premium().autoLogin());
                        return java.util.concurrent.CompletableFuture.completedFuture(JoinOutcome.REQUIRES_AUTH);
                    })
                    .whenComplete((outcome, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }

                        if (exception != null) {
                            debug("Join completion exception player=%s uuid=%s error=%s",
                                    player.getName(),
                                    player.getUniqueId(),
                                    exception.getMessage());
                            langConfig.send(player, "error-generic");
                            return;
                        }

                        debug("Join outcome player=%s uuid=%s outcome=%s", player.getName(), player.getUniqueId(), outcome);

                        switch (outcome) {
                            case SESSION_RESTORED -> {
                                playerProtection.removeProtection(player);
                                debug("Join protection removed by session restore player=%s uuid=%s", player.getName(), player.getUniqueId());
                                langConfig.send(player, "session-restored", Placeholder.unparsed("player", player.getName()));
                            }
                            case PREMIUM_AUTO_LOGIN -> {
                                playerProtection.removeProtection(player);
                                debug("Join protection removed by premium auto login player=%s uuid=%s", player.getName(), player.getUniqueId());
                                langConfig.send(player, "premium-auto-login");
                            }
                            case REQUIRES_AUTH -> langConfig.send(player, "not-authenticated");
                        }
                    }));
        } catch (Exception exception) {
            plugin.getLogger().severe("[DEBUG] Join hard failure: " + exception.getMessage());
            exception.printStackTrace();
        }
    }

    private String ipAddress(Player player) {
        return player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
    }

    private void debug(String template, Object... args) {
        if (!pluginConfig.debugEnabled()) {
            return;
        }
        plugin.getLogger().info("[DEBUG] " + template.formatted(args));
    }

    private enum JoinOutcome {
        SESSION_RESTORED,
        PREMIUM_AUTO_LOGIN,
        REQUIRES_AUTH
    }
}
