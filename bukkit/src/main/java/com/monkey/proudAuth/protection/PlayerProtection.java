package com.monkey.proudAuth.protection;

import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PlayerProtection {

    private final JavaPlugin plugin;
    private final Set<UUID> protectedPlayers;
    private final Set<UUID> claimChoicePlayers;
    private final Map<UUID, BukkitTask> timeoutTasks;
    private final Map<UUID, MovementStateSnapshot> movementStateSnapshots;
    private final Map<UUID, VisualEffectSnapshot> visualEffectSnapshots;
    private volatile PluginConfig pluginConfig;
    private volatile LangConfig langConfig;
    private final ProudAuthConsoleLogger logger;

    public PlayerProtection(JavaPlugin plugin, PluginConfig pluginConfig, LangConfig langConfig, ProudAuthConsoleLogger logger) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.logger = logger;
        this.protectedPlayers = ConcurrentHashMap.newKeySet();
        this.claimChoicePlayers = ConcurrentHashMap.newKeySet();
        this.timeoutTasks = new ConcurrentHashMap<>();
        this.movementStateSnapshots = new ConcurrentHashMap<>();
        this.visualEffectSnapshots = new ConcurrentHashMap<>();
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
        applyVisualEffect(player);
        refreshVisibility();
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
        applyVisualEffect(player);
        refreshVisibility();
    }

    public void removeProtection(Player player) {
        boolean wasProtected = protectedPlayers.remove(player.getUniqueId());
        claimChoicePlayers.remove(player.getUniqueId());
        BukkitTask timeoutTask = timeoutTasks.remove(player.getUniqueId());
        MovementStateSnapshot movementStateSnapshot = movementStateSnapshots.remove(player.getUniqueId());
        VisualEffectSnapshot visualEffectSnapshot = visualEffectSnapshots.remove(player.getUniqueId());
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        debugEvent("protection_remove",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "was_protected", wasProtected,
                "timeout_task", timeoutTask != null,
                "movement_snapshot", movementStateSnapshot != null,
                "visual_effect_snapshot", visualEffectSnapshot != null);
        if (movementStateSnapshot != null) {
            restoreMovementState(player, movementStateSnapshot);
        }
        restoreVisualEffect(player, visualEffectSnapshot);
        refreshVisibility();
    }

    public boolean isProtected(UUID uuid) {
        return protectedPlayers.contains(uuid);
    }

    public void enterClaimChoicePhase(Player player) {
        claimChoicePlayers.add(player.getUniqueId());
    }

    public void exitClaimChoicePhase(Player player) {
        claimChoicePlayers.remove(player.getUniqueId());
    }

    public boolean isInClaimChoicePhase(UUID uuid) {
        return claimChoicePlayers.contains(uuid);
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

    public boolean blockCommands() {
        return pluginConfig.settings().protection().blockCommands();
    }

    public Set<String> allowedCommandsWhileProtected() {
        return pluginConfig.settings().protection().allowedCommandsWhileProtected().stream()
                .map(this::normalizeCommand)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<String> allowedCommandsDuringClaimChoice() {
        return pluginConfig.settings().protection().allowedCommandsDuringClaimChoice().stream()
                .map(this::normalizeCommand)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean blockBlockBreak() {
        return pluginConfig.settings().protection().blockBlockBreak();
    }

    public boolean blockBlockPlace() {
        return pluginConfig.settings().protection().blockBlockPlace();
    }

    public boolean blockPvPAttack() {
        return pluginConfig.settings().protection().blockPvPAttack();
    }

    public boolean blockPvPTakeDamage() {
        return pluginConfig.settings().protection().blockPvPTakeDamage();
    }

    public boolean blockItemPickup() {
        return pluginConfig.settings().protection().blockItemPickup();
    }

    public boolean blockFoodLevelChange() {
        return pluginConfig.settings().protection().blockFoodLevelChange();
    }

    public boolean blockItemConsume() {
        return pluginConfig.settings().protection().blockItemConsume();
    }

    public boolean blockSwapHandItems() {
        return pluginConfig.settings().protection().blockSwapHandItems();
    }

    public boolean blockBookEdit() {
        return pluginConfig.settings().protection().blockBookEdit();
    }

    public boolean blockInventory() {
        return pluginConfig.settings().protection().blockInventory();
    }

    public boolean blockItemDrop() {
        return pluginConfig.settings().protection().blockItemDrop();
    }

    public boolean blockEntityInteract() {
        return pluginConfig.settings().protection().blockEntityInteract();
    }

    public boolean blockWorldInteract() {
        return pluginConfig.settings().protection().blockWorldInteract();
    }

    public com.monkey.proudAuth.common.config.ProudAuthSettings.VisualEffect visualEffect() {
        return pluginConfig.settings().protection().visualEffect();
    }

    public void refreshVisibility() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (viewer.getUniqueId().equals(target.getUniqueId())) {
                    continue;
                }
                if (shouldHide(viewer, target)) {
                    viewer.hidePlayer(plugin, target);
                } else {
                    viewer.showPlayer(plugin, target);
                }
            }
        }
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

    private boolean shouldHide(Player viewer, Player target) {
        boolean hideProtectedPlayerFromOthers = pluginConfig.settings().protection().hideProtectedPlayerFromOthers();
        boolean hideOtherPlayersFromProtectedPlayer = pluginConfig.settings().protection().hideOtherPlayersFromProtectedPlayer();
        return hideProtectedPlayerFromOthers && isProtected(target.getUniqueId())
                || hideOtherPlayersFromProtectedPlayer && isProtected(viewer.getUniqueId());
    }

    private void applyVisualEffect(Player player) {
        PotionEffectType effectType = resolveEffectType();
        if (effectType == null) {
            return;
        }
        visualEffectSnapshots.computeIfAbsent(player.getUniqueId(), ignored ->
                new VisualEffectSnapshot(effectType, player.getPotionEffect(effectType)));
        player.addPotionEffect(new PotionEffect(effectType, Integer.MAX_VALUE, 0, false, false, false));
    }

    private void restoreVisualEffect(Player player, VisualEffectSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        player.removePotionEffect(snapshot.effectType());
        if (snapshot.previousEffect() != null) {
            player.addPotionEffect(snapshot.previousEffect());
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

    private String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private PotionEffectType resolveEffectType() {
        return switch (visualEffect()) {
            case BLINDNESS -> PotionEffectType.BLINDNESS;
            case DARKNESS -> PotionEffectType.DARKNESS;
            case NONE -> null;
        };
    }

    private record VisualEffectSnapshot(
            PotionEffectType effectType,
            PotionEffect previousEffect
    ) {
    }
}
