package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import com.monkey.proudAuth.wrapper.SchedulerCoordinator;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerChatListener implements Listener {

    private final JavaPlugin plugin;
    private final SchedulerCoordinator schedulerCoordinator;
    private final PlayerProtection playerProtection;
    private final AuthService authService;
    private final LangConfig langConfig;

    public PlayerChatListener(JavaPlugin plugin, SchedulerCoordinator schedulerCoordinator, PlayerProtection playerProtection, AuthService authService, LangConfig langConfig) {
        this.plugin = plugin;
        this.schedulerCoordinator = schedulerCoordinator;
        this.playerProtection = playerProtection;
        this.authService = authService;
        this.langConfig = langConfig;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (!playerProtection.blockChat() || !playerProtection.isProtected(event.getPlayer().getUniqueId())) {
            return;
        }
        if (authService.hasPendingTotpChallenge(event.getPlayer().getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        schedulerCoordinator.runPlayer(event.getPlayer(), () -> langConfig.send(event.getPlayer(), "chat-blocked"));
    }
}
