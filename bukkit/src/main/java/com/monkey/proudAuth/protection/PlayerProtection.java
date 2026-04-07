package com.monkey.proudAuth.protection;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerProtection {

    private final JavaPlugin plugin;
    private final Set<UUID> protectedPlayers;
    private final Map<UUID, BukkitTask> timeoutTasks;
    private volatile PluginConfig pluginConfig;
    private volatile LangConfig langConfig;
    private final ProudAuthConsoleLogger logger;

    public PlayerProtection(JavaPlugin plugin, PluginConfig pluginConfig, LangConfig langConfig, ProudAuthConsoleLogger logger) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.logger = logger;
        this.protectedPlayers = ConcurrentHashMap.newKeySet();
        this.timeoutTasks = new ConcurrentHashMap<>();
    }

    public void reload(PluginConfig pluginConfig, LangConfig langConfig) {
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
    }

    public void applyProtection(Player player) {
        protectedPlayers.add(player.getUniqueId());
        debug("Protection apply player=%s uuid=%s authSpawnEnabled=%s",
                player.getName(),
                player.getUniqueId(),
                pluginConfig.settings().protection().authSpawn().enabled());
        pluginConfig.authSpawn(plugin.getServer()).ifPresent(player::teleportAsync);
        applyInvisibility(player);
        scheduleAuthTimeout(player);
    }

    public void removeProtection(Player player) {
        boolean wasProtected = protectedPlayers.remove(player.getUniqueId());
        BukkitTask timeoutTask = timeoutTasks.remove(player.getUniqueId());
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        debug("Protection remove player=%s uuid=%s wasProtected=%s timeoutTask=%s",
                player.getName(),
                player.getUniqueId(),
                wasProtected,
                timeoutTask != null);
        if (wasProtected) {
            removeInvisibility(player);
        }
    }

    public boolean isProtected(UUID uuid) {
        return protectedPlayers.contains(uuid);
    }

    public boolean blockMovement() {
        return pluginConfig.settings().protection().blockMovement();
    }

    public boolean blockChat() {
        return pluginConfig.settings().protection().blockChat();
    }

    public boolean blockInteractions() {
        return pluginConfig.settings().protection().blockInteractions();
    }

    public boolean noDropOnDeath() {
        return pluginConfig.settings().protection().noDropOnDeath();
    }

    public void clear(Player player) {
        removeProtection(player);
    }

    private void debug(String template, Object... args) {
        logger.debug(pluginConfig.settings().debugger(), DebugChannel.PROTECTION_FLOW, template, args);
    }

    private void scheduleAuthTimeout(Player player) {
        BukkitTask previousTask = timeoutTasks.remove(player.getUniqueId());
        if (previousTask != null) {
            previousTask.cancel();
        }

        long timeoutTicks = Math.max(20L, pluginConfig.settings().protection().authTimeoutSeconds() * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline() || !isProtected(player.getUniqueId())) {
                return;
            }
            player.kick(langConfig.message("kick-auth-timeout"));
        }, timeoutTicks);
        timeoutTasks.put(player.getUniqueId(), task);
    }

    private void applyInvisibility(Player player) {
        if (!pluginConfig.settings().protection().invisibleUntilAuth()) {
            return;
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            other.hidePlayer(plugin, player);
            player.hidePlayer(plugin, other);
        }
    }

    private void removeInvisibility(Player player) {
        if (!pluginConfig.settings().protection().invisibleUntilAuth()) {
            return;
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            other.showPlayer(plugin, player);
            player.showPlayer(plugin, other);
        }
    }
}
