package com.monkey.proudAuth.protection;

import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
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
    private final Map<UUID, MovementStateSnapshot> movementStateSnapshots;
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
        this.movementStateSnapshots = new ConcurrentHashMap<>();
    }

    public void reload(PluginConfig pluginConfig, LangConfig langConfig) {
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
    }

    public void applyProtection(Player player) {
        protectedPlayers.add(player.getUniqueId());
        captureMovementState(player);
        debugEvent("protection_apply",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "auth_spawn_enabled", pluginConfig.settings().protection().authSpawn().enabled());
        pluginConfig.authSpawn(plugin.getServer()).ifPresent(player::teleportAsync);
        applyInvisibility(player);
        scheduleAuthTimeout(player);
    }

    public void applyProtectionTransient(Player player) {
        protectedPlayers.add(player.getUniqueId());
        captureMovementState(player);
        debugEvent("protection_apply_transient",
                "player", player.getName(),
                "uuid", player.getUniqueId());
        scheduleAuthTimeout(player);
    }

    public void upgradeToFullProtection(Player player) {
        debugEvent("protection_upgrade_full",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "auth_spawn_enabled", pluginConfig.settings().protection().authSpawn().enabled());
        pluginConfig.authSpawn(plugin.getServer()).ifPresent(player::teleportAsync);
        applyInvisibility(player);
    }

    public void removeProtection(Player player) {
        boolean wasProtected = protectedPlayers.remove(player.getUniqueId());
        BukkitTask timeoutTask = timeoutTasks.remove(player.getUniqueId());
        MovementStateSnapshot movementStateSnapshot = movementStateSnapshots.remove(player.getUniqueId());
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        debugEvent("protection_remove",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "was_protected", wasProtected,
                "timeout_task", timeoutTask != null,
                "movement_snapshot", movementStateSnapshot != null);
        if (movementStateSnapshot != null) {
            restoreMovementState(player, movementStateSnapshot);
        }
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

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), DebugChannel.PROTECTION_FLOW, eventName, keyValues);
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

    private void captureMovementState(Player player) {
        movementStateSnapshots.putIfAbsent(
                player.getUniqueId(),
                new MovementStateSnapshot(
                        player.getWalkSpeed(),
                        player.getFlySpeed(),
                        player.getAllowFlight(),
                        player.isFlying(),
                        player.isInvulnerable()
                )
        );
    }

    private void restoreMovementState(Player player, MovementStateSnapshot snapshot) {
        float walkSpeed = Math.abs(snapshot.walkSpeed()) < 1.0E-6F ? 0.2F : snapshot.walkSpeed();
        float flySpeed = Math.abs(snapshot.flySpeed()) < 1.0E-6F ? 0.1F : snapshot.flySpeed();

        player.setWalkSpeed(walkSpeed);
        player.setFlySpeed(flySpeed);
        player.setAllowFlight(snapshot.allowFlight());
        player.setFlying(snapshot.allowFlight() && snapshot.flying());
        player.setInvulnerable(snapshot.invulnerable());
        player.setFreezeTicks(0);

        debugEvent("protection_restore_movement_state",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "walk_speed", walkSpeed,
                "fly_speed", flySpeed,
                "allow_flight", snapshot.allowFlight(),
                "flying", snapshot.allowFlight() && snapshot.flying(),
                "invulnerable", snapshot.invulnerable());
    }

    private record MovementStateSnapshot(
            float walkSpeed,
            float flySpeed,
            boolean allowFlight,
            boolean flying,
            boolean invulnerable
    ) {
    }
}
