package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.update.UpdateCheckResult;
import com.monkey.proudAuth.wrapper.SchedulerCoordinator;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Supplier;

public final class AdminUpdateNotifyListener implements Listener {

    private final JavaPlugin plugin;
    private final SchedulerCoordinator schedulerCoordinator;
    private final LangConfig langConfig;
    private final Supplier<Boolean> notifyEnabledSupplier;
    private final Supplier<UpdateCheckResult> updateResultSupplier;

    public AdminUpdateNotifyListener(
            JavaPlugin plugin,
            SchedulerCoordinator schedulerCoordinator,
            LangConfig langConfig,
            Supplier<Boolean> notifyEnabledSupplier,
            Supplier<UpdateCheckResult> updateResultSupplier
    ) {
        this.plugin = plugin;
        this.schedulerCoordinator = schedulerCoordinator;
        this.langConfig = langConfig;
        this.notifyEnabledSupplier = notifyEnabledSupplier;
        this.updateResultSupplier = updateResultSupplier;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!notifyEnabledSupplier.get()) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("proudauth.admin.update") && !player.hasPermission("proudauth.admin")) {
            return;
        }
        UpdateCheckResult result = updateResultSupplier.get();
        if (!result.checked() || !result.updateAvailable()) {
            return;
        }

        schedulerCoordinator.runPlayerLater(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            langConfig.send(
                    player,
                    "update-admin-notify",
                    Placeholder.unparsed("current", result.currentVersion()),
                    Placeholder.unparsed("latest", result.latestVersion()),
                    Placeholder.unparsed("url", result.resourceUrl())
            );
        }, 40L);
    }
}
