package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class PlayerDeathListener implements Listener {

    private final PlayerProtection playerProtection;

    public PlayerDeathListener(PlayerProtection playerProtection) {
        this.playerProtection = playerProtection;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDeath(PlayerDeathEvent event) {
        if (!playerProtection.noDropOnDeath() || !playerProtection.isProtected(event.getPlayer().getUniqueId())) {
            return;
        }

        event.getDrops().clear();
    }
}
