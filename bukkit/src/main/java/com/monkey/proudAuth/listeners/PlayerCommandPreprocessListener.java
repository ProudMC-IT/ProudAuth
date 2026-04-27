package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.Set;

public final class PlayerCommandPreprocessListener implements Listener {

    private static final Set<String> ALLOWED = Set.of("/login", "/register", "/2fa", "/proudauthbackend");

    private final PlayerProtection playerProtection;
    private final LangConfig langConfig;

    public PlayerCommandPreprocessListener(PlayerProtection playerProtection, LangConfig langConfig) {
        this.playerProtection = playerProtection;
        this.langConfig = langConfig;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!playerProtection.isProtected(event.getPlayer().getUniqueId())) {
            return;
        }

        String command = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (ALLOWED.contains(command)) {
            return;
        }

        event.setCancelled(true);
        langConfig.send(event.getPlayer(), "command-blocked");
    }
}
