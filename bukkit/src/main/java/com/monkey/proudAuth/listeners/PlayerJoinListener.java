package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.auth.BukkitJoinFlowService;
import com.monkey.proudAuth.auth.ResolvedLogin;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final PluginConfig pluginConfig;
    private final PlayerPreLoginListener playerPreLoginListener;
    private final BukkitJoinFlowService joinFlowService;
    private final ProudAuthConsoleLogger logger;

    public PlayerJoinListener(
            PluginConfig pluginConfig,
            PlayerPreLoginListener playerPreLoginListener,
            BukkitJoinFlowService joinFlowService,
            ProudAuthConsoleLogger logger
    ) {
        this.pluginConfig = pluginConfig;
        this.playerPreLoginListener = playerPreLoginListener;
        this.joinFlowService = joinFlowService;
        this.logger = logger;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            Player player = event.getPlayer();
            java.util.Optional<ResolvedLogin> consumed = playerPreLoginListener.consume(player.getUniqueId());
            ResolvedLogin resolvedLogin = consumed
                    .orElseGet(() -> new ResolvedLogin(
                            player.getUniqueId(),
                            player.getName(),
                            player.getUniqueId().equals(PremiumVerifier.offlineUuid(player.getName())) ? AccountType.CRACKED : AccountType.PREMIUM,
                            ipAddress(player)
                    ));
            debug(DebugChannel.PLAYER_RESOLUTION, "Join player=%s uuid=%s preloginCacheHit=%s resolvedName=%s accountType=%s ip=%s",
                    player.getName(),
                    player.getUniqueId(),
                    consumed.isPresent(),
                    resolvedLogin.username(),
                    resolvedLogin.accountType(),
                    resolvedLogin.ipAddress());
            joinFlowService.handleJoin(player, resolvedLogin);
        } catch (Exception exception) {
            logger.error("Join hard failure.", exception);
        }
    }

    private void debug(DebugChannel channel, String template, Object... args) {
        logger.debug(pluginConfig.settings().debugger(), channel, template, args);
    }

    private String ipAddress(Player player) {
        return player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
    }
}
