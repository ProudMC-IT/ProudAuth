package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.RegisteredListener;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerMoveListener implements Listener {

    private final PlayerProtection playerProtection;
    private final LangConfig langConfig;
    private final com.monkey.proudAuth.config.PluginConfig pluginConfig;
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final Map<UUID, Long> lastNotice;
    private final Map<PlayerMoveEvent, MoveAudit> moveAudits;
    private final AtomicBoolean dumpedExternalMoveListeners;

    public PlayerMoveListener(
            org.bukkit.plugin.java.JavaPlugin plugin,
            com.monkey.proudAuth.config.PluginConfig pluginConfig,
            PlayerProtection playerProtection,
            LangConfig langConfig
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.playerProtection = playerProtection;
        this.langConfig = langConfig;
        this.lastNotice = new ConcurrentHashMap<>();
        this.moveAudits = Collections.synchronizedMap(new IdentityHashMap<>());
        this.dumpedExternalMoveListeners = new AtomicBoolean(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && pluginConfig.debugEnabled()) {
            boolean attemptedHorizontal = event.getFrom().getX() != event.getTo().getX()
                    || event.getFrom().getZ() != event.getTo().getZ();
            boolean attemptedVertical = event.getFrom().getY() != event.getTo().getY();
            if (attemptedHorizontal || attemptedVertical) {
                moveAudits.put(event, new MoveAudit(
                        event.getPlayer().getName(),
                        event.getPlayer().getUniqueId(),
                        event.getFrom().getX(),
                        event.getFrom().getY(),
                        event.getFrom().getZ(),
                        event.getTo().getX(),
                        event.getTo().getY(),
                        event.getTo().getZ(),
                        attemptedHorizontal
                ));
            }
        }

        if (!playerProtection.blockMovement() || !playerProtection.isProtected(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        if (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ()) {
            return;
        }

        debug("Move blocked player=%s uuid=%s from=%s,%s,%s to=%s,%s,%s",
                event.getPlayer().getName(),
                event.getPlayer().getUniqueId(),
                event.getFrom().getX(),
                event.getFrom().getY(),
                event.getFrom().getZ(),
                event.getTo().getX(),
                event.getTo().getY(),
                event.getTo().getZ());
        event.setTo(event.getFrom());
        sendNotice(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onMoveMonitor(PlayerMoveEvent event) {
        MoveAudit audit = moveAudits.remove(event);
        if (audit == null || !pluginConfig.debugEnabled() || event.getTo() == null) {
            return;
        }
        if (playerProtection.isProtected(audit.uuid())) {
            return;
        }

        boolean finalSameHorizontal = audit.fromX() == event.getTo().getX()
                && audit.fromZ() == event.getTo().getZ();
        if (!audit.attemptedHorizontal() || (!event.isCancelled() && !finalSameHorizontal)) {
            return;
        }

        debug("External move suppression suspected player=%s uuid=%s cancelled=%s originalTo=%s,%s,%s finalTo=%s,%s,%s",
                audit.playerName(),
                audit.uuid(),
                event.isCancelled(),
                audit.originalToX(),
                audit.originalToY(),
                audit.originalToZ(),
                event.getTo().getX(),
                event.getTo().getY(),
                event.getTo().getZ());
        dumpExternalMoveListenersOnce();
    }

    private void sendNotice(Player player) {
        long now = System.currentTimeMillis();
        long previous = lastNotice.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < 1000L) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        langConfig.send(player, "move-blocked");
    }

    private void debug(String template, Object... args) {
        if (!pluginConfig.debugEnabled()) {
            return;
        }
        plugin.getLogger().info("[DEBUG] " + template.formatted(args));
    }

    private void dumpExternalMoveListenersOnce() {
        if (!dumpedExternalMoveListeners.compareAndSet(false, true)) {
            return;
        }
        for (RegisteredListener listener : PlayerMoveEvent.getHandlerList().getRegisteredListeners()) {
            debug("PlayerMove listener plugin=%s priority=%s listener=%s ignoreCancelled=%s",
                    listener.getPlugin().getName(),
                    listener.getPriority(),
                    listener.getListener().getClass().getName(),
                    listener.isIgnoringCancelled());
        }
    }

    private record MoveAudit(
            String playerName,
            UUID uuid,
            double fromX,
            double fromY,
            double fromZ,
            double originalToX,
            double originalToY,
            double originalToZ,
            boolean attemptedHorizontal
    ) {
    }
}
