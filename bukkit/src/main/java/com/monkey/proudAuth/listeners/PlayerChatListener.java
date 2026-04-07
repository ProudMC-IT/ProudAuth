package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

public final class PlayerChatListener implements Listener {

    private final JavaPlugin plugin;
    private final PlayerProtection playerProtection;
    private final LangConfig langConfig;

    public PlayerChatListener(JavaPlugin plugin, PlayerProtection playerProtection, LangConfig langConfig) {
        this.plugin = plugin;
        this.playerProtection = playerProtection;
        this.langConfig = langConfig;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        if (!playerProtection.blockChat() || !playerProtection.isProtected(event.getPlayer().getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> langConfig.send(event.getPlayer(), "chat-blocked"));
    }
}
