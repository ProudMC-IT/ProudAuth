package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerInteractListener implements Listener {

    private final PlayerProtection playerProtection;
    private final LangConfig langConfig;
    private final Map<UUID, Long> lastNotice;

    public PlayerInteractListener(PlayerProtection playerProtection, LangConfig langConfig) {
        this.playerProtection = playerProtection;
        this.langConfig = langConfig;
        this.lastNotice = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (shouldBlock(event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (shouldBlock(event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && shouldBlock(player)) {
            event.setCancelled(true);
            sendNotice(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && shouldBlock(player)) {
            event.setCancelled(true);
            sendNotice(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && shouldBlock(player)) {
            event.setCancelled(true);
            sendNotice(player);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (shouldBlock(event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer());
        }
    }

    private boolean shouldBlock(Player player) {
        return playerProtection.blockInteractions() && playerProtection.isProtected(player.getUniqueId());
    }

    private void sendNotice(Player player) {
        long now = System.currentTimeMillis();
        long previous = lastNotice.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < 1000L) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        langConfig.send(player, "interact-blocked");
    }
}
