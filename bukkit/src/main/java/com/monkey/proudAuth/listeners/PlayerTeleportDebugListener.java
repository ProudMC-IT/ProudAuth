package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class PlayerTeleportDebugListener implements Listener {

    private final PluginConfig pluginConfig;
    private final PlayerProtection playerProtection;
    private final ProudAuthConsoleLogger logger;

    public PlayerTeleportDebugListener(PluginConfig pluginConfig, PlayerProtection playerProtection, ProudAuthConsoleLogger logger) {
        this.pluginConfig = pluginConfig;
        this.playerProtection = playerProtection;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!pluginConfig.settings().debugger().isEnabled(DebugChannel.TELEPORT_AUDIT) || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == to.getWorld()
                && from.getX() == to.getX()
                && from.getY() == to.getY()
                && from.getZ() == to.getZ()
                && from.getYaw() == to.getYaw()
                && from.getPitch() == to.getPitch()) {
            return;
        }

        logger.debug(pluginConfig.settings().debugger(), DebugChannel.TELEPORT_AUDIT,
                "Teleport observed player=%s uuid=%s cause=%s cancelled=%s protected=%s from=%s,%s,%s,%s,%s to=%s,%s,%s,%s,%s",
                event.getPlayer().getName(),
                event.getPlayer().getUniqueId(),
                event.getCause(),
                event.isCancelled(),
                playerProtection.isProtected(event.getPlayer().getUniqueId()),
                from.getWorld() == null ? "null" : from.getWorld().getName(),
                from.getX(),
                from.getY(),
                from.getZ(),
                from.getYaw(),
                to.getWorld() == null ? "null" : to.getWorld().getName(),
                to.getX(),
                to.getY(),
                to.getZ(),
                to.getYaw()
        );
    }
}
