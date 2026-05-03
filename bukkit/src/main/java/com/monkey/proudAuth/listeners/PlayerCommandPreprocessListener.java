package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;

public final class PlayerCommandPreprocessListener implements Listener {

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
        if (!playerProtection.blockCommands()) {
            return;
        }

        String command = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (playerProtection.isInClaimChoicePhase(event.getPlayer().getUniqueId())) {
            if (playerProtection.allowedCommandsDuringClaimChoice().contains(command)) {
                return;
            }
            event.setCancelled(true);
            langConfig.send(event.getPlayer(), "claim-command-blocked");
            return;
        }

        if (playerProtection.allowedCommandsWhileProtected().contains(command)) {
            return;
        }

        event.setCancelled(true);
        langConfig.send(event.getPlayer(), "command-blocked");
    }
}
