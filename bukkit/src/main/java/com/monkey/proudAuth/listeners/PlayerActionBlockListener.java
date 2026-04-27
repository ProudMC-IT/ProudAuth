package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerActionBlockListener implements Listener {

    private final PlayerProtection playerProtection;
    private final LangConfig langConfig;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    public PlayerActionBlockListener(PlayerProtection playerProtection, LangConfig langConfig) {
        this.playerProtection = playerProtection;
        this.langConfig = langConfig;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "block-break-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
            sendNotice(event.getPlayer(), "block-break-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker && isProtected(attacker)) {
            event.setCancelled(true);
            sendNotice(attacker, "pvp-blocked");
            return;
        }
        if (event.getEntity() instanceof Player victim && isProtected(victim)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onFoodLevelChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isProtected(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onEditBook(PlayerEditBookEvent event) {
        if (isProtected(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean isProtected(Player player) {
        return playerProtection.blockInteractions() && playerProtection.isProtected(player.getUniqueId());
    }

    private void sendNotice(Player player, String key) {
        long now = System.currentTimeMillis();
        if (now - lastNotice.getOrDefault(player.getUniqueId(), 0L) < 1000L) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        langConfig.send(player, key);
    }
}
