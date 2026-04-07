package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final AuthService authService;
    private final PlayerProtection playerProtection;

    public PlayerQuitListener(AuthService authService, PlayerProtection playerProtection) {
        this.authService = authService;
        this.playerProtection = playerProtection;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerProtection.clear(event.getPlayer());
        authService.untrackPlayer(event.getPlayer().getUniqueId());
    }
}
