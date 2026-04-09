package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitJoinFlowService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final SessionManager sessionManager;
    private final PlayerProtection playerProtection;
    private final ProudAuthConsoleLogger logger;

    public BukkitJoinFlowService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            SessionManager sessionManager,
            PlayerProtection playerProtection,
            ProudAuthConsoleLogger logger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.playerProtection = playerProtection;
        this.logger = logger;
    }

    public void handleJoin(Player player, ResolvedLogin resolvedLogin) {
        authService.trackPlayer(player.getUniqueId(), player.getName(), resolvedLogin.accountType());

        boolean rawBypassPermission = player.hasPermission("proudauth.bypass.auth");
        boolean bypassAuth = rawBypassPermission && resolvedLogin.accountType() != AccountType.PREMIUM;
        debug(DebugChannel.PLAYER_RESOLUTION, "Join bypass check player=%s uuid=%s rawBypass=%s effectiveBypass=%s accountType=%s",
                player.getName(),
                player.getUniqueId(),
                rawBypassPermission,
                bypassAuth,
                resolvedLogin.accountType());

        if (bypassAuth) {
            handleBypassJoin(player, resolvedLogin);
            return;
        }

        if (resolvedLogin.accountType() == AccountType.PREMIUM && pluginConfig.settings().premium().autoLogin()) {
            handlePremiumAutoLogin(player, resolvedLogin);
            return;
        }

        handleProtectedJoin(player, resolvedLogin);
    }

    private void handleBypassJoin(Player player, ResolvedLogin resolvedLogin) {
        debug(DebugChannel.SECURITY_FLOW, "Join bypass branch player=%s uuid=%s", player.getName(), player.getUniqueId());
        authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.AUTHENTICATED, ipAddress(player))
                .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (exception != null) {
                        debug(DebugChannel.SECURITY_FLOW, "Join bypass exception player=%s uuid=%s error=%s",
                                player.getName(),
                                player.getUniqueId(),
                                exception.getMessage());
                        langConfig.send(player, "error-generic");
                        return;
                    }
                    playerProtection.removeProtection(player);
                    debug(DebugChannel.SECURITY_FLOW, "Join bypass completed player=%s uuid=%s", player.getName(), player.getUniqueId());
                }));
    }

    private void handlePremiumAutoLogin(Player player, ResolvedLogin resolvedLogin) {
        debug(DebugChannel.PREMIUM_FLOW, "Join premium fast-path player=%s uuid=%s", player.getName(), player.getUniqueId());
        authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.PREMIUM_AUTO, resolvedLogin.ipAddress())
                .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (exception != null) {
                        debug(DebugChannel.PREMIUM_FLOW, "Join premium fast-path exception player=%s uuid=%s error=%s",
                                player.getName(),
                                player.getUniqueId(),
                                exception.getMessage());
                        langConfig.send(player, "error-generic");
                        return;
                    }
                    playerProtection.removeProtection(player);
                    debug(DebugChannel.PREMIUM_FLOW, "Join premium fast-path completed player=%s uuid=%s", player.getName(), player.getUniqueId());
                    langConfig.send(player, "premium-auto-login");
                }));
    }

    private void handleProtectedJoin(Player player, ResolvedLogin resolvedLogin) {
        playerProtection.applyProtection(player);
        debug(DebugChannel.PROTECTION_FLOW, "Join protection applied player=%s uuid=%s", player.getName(), player.getUniqueId());

        sessionManager.findValid(player.getUniqueId(), resolvedLogin.ipAddress())
                .thenCompose(optionalSession -> {
                    debug(DebugChannel.SESSION_FLOW, "Join session lookup player=%s uuid=%s sessionPresent=%s",
                            player.getName(),
                            player.getUniqueId(),
                            optionalSession.isPresent());
                    if (optionalSession.isPresent()) {
                        return authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.AUTHENTICATED, resolvedLogin.ipAddress())
                                .thenApply(ignored -> JoinOutcome.SESSION_RESTORED);
                    }

                    debug(DebugChannel.SESSION_FLOW, "Join requires manual auth player=%s uuid=%s accountType=%s premiumAutoLogin=%s",
                            player.getName(),
                            player.getUniqueId(),
                            resolvedLogin.accountType(),
                            pluginConfig.settings().premium().autoLogin());
                    return java.util.concurrent.CompletableFuture.completedFuture(JoinOutcome.REQUIRES_AUTH);
                })
                .whenComplete((outcome, exception) -> Bukkit.getScheduler().runTask(plugin, () -> completeProtectedJoin(player, outcome, exception)));
    }

    private void completeProtectedJoin(Player player, JoinOutcome outcome, Throwable exception) {
        if (!player.isOnline()) {
            return;
        }

        if (exception != null) {
            debug(DebugChannel.SESSION_FLOW, "Join completion exception player=%s uuid=%s error=%s",
                    player.getName(),
                    player.getUniqueId(),
                    exception.getMessage());
            langConfig.send(player, "error-generic");
            return;
        }

        debug(DebugChannel.SESSION_FLOW, "Join outcome player=%s uuid=%s outcome=%s", player.getName(), player.getUniqueId(), outcome);
        switch (outcome) {
            case SESSION_RESTORED -> {
                playerProtection.removeProtection(player);
                debug(DebugChannel.PROTECTION_FLOW, "Join protection removed by session restore player=%s uuid=%s", player.getName(), player.getUniqueId());
                langConfig.send(player, "session-restored", Placeholder.unparsed("player", player.getName()));
            }
            case REQUIRES_AUTH -> langConfig.send(player, "not-authenticated");
        }
    }

    private String ipAddress(Player player) {
        return player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
    }

    private void debug(DebugChannel channel, String template, Object... args) {
        logger.debug(pluginConfig.settings().debugger(), channel, template, args);
    }

    private enum JoinOutcome {
        SESSION_RESTORED,
        REQUIRES_AUTH
    }
}
