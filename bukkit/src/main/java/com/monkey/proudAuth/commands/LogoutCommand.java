package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorState;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.network.BackendNetworkSyncService;
import com.monkey.proudAuth.protection.PlayerProtection;
import com.monkey.proudAuth.wrapper.SchedulerCoordinator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class LogoutCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final SchedulerCoordinator schedulerCoordinator;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final PlayerProtection playerProtection;
    private final BackendNetworkSyncService networkSyncService;

    public LogoutCommand(
            JavaPlugin plugin,
            SchedulerCoordinator schedulerCoordinator,
            LangConfig langConfig,
            AuthService authService,
            PlayerProtection playerProtection,
            BackendNetworkSyncService networkSyncService
    ) {
        this.plugin = plugin;
        this.schedulerCoordinator = schedulerCoordinator;
        this.langConfig = langConfig;
        this.authService = authService;
        this.playerProtection = playerProtection;
        this.networkSyncService = networkSyncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            langConfig.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("proudauth.logout")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (args.length != 0) {
            langConfig.send(player, "error-usage", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("usage", "/logout"));
            return true;
        }
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }

        authService.logout(player.getUniqueId()).whenComplete((ignored, exception) -> schedulerCoordinator.runPlayer(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }

            playerProtection.applyProtection(player);
            networkSyncService.notifyMonitorState(player, ProudAuthMonitorState.WAITING_LOGIN);
            networkSyncService.notifyAuthInvalidated(player);
            langConfig.send(player, "logout-success");
            langConfig.send(player, "not-authenticated");
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
