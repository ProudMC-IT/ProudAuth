package com.monkey.proudAuth.commands.admin;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.storage.StatsStorage;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class ProudAuthAdminCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final PlayerProtection playerProtection;
    private final BruteForceGuard bruteForceGuard;
    private final StatsStorage storage;
    private final Runnable reloadAction;

    public ProudAuthAdminCommand(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            PlayerProtection playerProtection,
            BruteForceGuard bruteForceGuard,
            StatsStorage storage,
            Runnable reloadAction
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.authService = authService;
        this.playerProtection = playerProtection;
        this.bruteForceGuard = bruteForceGuard;
        this.storage = storage;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("proudauth.admin")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth <forcelogin|forcelogout|resetpassword|banip|unbanip|stats|reload>"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "forcelogin" -> handleForceLogin(sender, args);
            case "forcelogout" -> handleForceLogout(sender, args);
            case "resetpassword" -> handleResetPassword(sender, args);
            case "banip" -> handleBanIp(sender, args);
            case "unbanip" -> handleUnbanIp(sender, args);
            case "stats" -> handleStats(sender);
            case "reload" -> handleReload(sender);
            default -> {
                langConfig.send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("forcelogin", "forcelogout", "resetpassword", "banip", "unbanip", "stats", "reload"), args[0]);
        }
        if (args.length == 2 && ("forcelogin".equalsIgnoreCase(args[0]) || "forcelogout".equalsIgnoreCase(args[0]))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        return Collections.emptyList();
    }

    private boolean handleForceLogin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.forcelogin")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth forcelogin <player>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            langConfig.send(sender, "admin-player-not-found", Placeholder.unparsed("player", args[1]));
            return true;
        }

        AccountType accountType = authService.player(target.getUniqueId()).map(auth -> auth.accountType()).orElse(AccountType.CRACKED);
        String ip = target.getAddress() == null ? "unknown" : target.getAddress().getAddress().getHostAddress();
        authService.autoAuthenticate(target.getUniqueId(), target.getName(), accountType, AuthState.AUTHENTICATED, ip).whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            playerProtection.removeProtection(target);
            langConfig.send(sender, "admin-force-login", Placeholder.unparsed("player", target.getName()));
        }));
        return true;
    }

    private boolean handleForceLogout(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.forcelogout")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth forcelogout <player>"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            langConfig.send(sender, "admin-player-not-found", Placeholder.unparsed("player", args[1]));
            return true;
        }

        authService.logout(target.getUniqueId()).whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            playerProtection.applyProtection(target);
            langConfig.send(sender, "admin-force-logout", Placeholder.unparsed("player", target.getName()));
        }));
        return true;
    }

    private boolean handleResetPassword(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.resetpassword")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length != 3) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth resetpassword <player> <nuova>"));
            return true;
        }

        authService.resetPassword(args[1], args[2]).whenComplete((updated, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            if (!updated) {
                langConfig.send(sender, "admin-player-not-found", Placeholder.unparsed("player", args[1]));
                return;
            }
            langConfig.send(sender, "admin-reset-password", Placeholder.unparsed("player", args[1]));
        }));
        return true;
    }

    private boolean handleBanIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.banip")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2 || args.length > 3) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth banip <ip> [secondi]"));
            return true;
        }

        long seconds = args.length == 3 ? parseLong(args[2], pluginConfig.settings().security().lockoutSeconds()) : pluginConfig.settings().security().lockoutSeconds();
        bruteForceGuard.banIp(args[1], seconds, "Ban manuale").whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            langConfig.send(sender, "admin-ip-banned", Placeholder.unparsed("ip", args[1]));
        }));
        return true;
    }

    private boolean handleUnbanIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.unbanip")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth unbanip <ip>"));
            return true;
        }

        bruteForceGuard.unbanIp(args[1]).whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            langConfig.send(sender, "admin-ip-unbanned", Placeholder.unparsed("ip", args[1]));
        }));
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!sender.hasPermission("proudauth.admin.stats")) {
            langConfig.send(sender, "no-permission");
            return true;
        }

        storage.fetchStats().whenComplete((stats, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            langConfig.send(sender, "stats-header");
            langConfig.send(sender, "stats-registered", Placeholder.unparsed("count", String.valueOf(stats.registeredAccounts())));
            langConfig.send(sender, "stats-online", Placeholder.unparsed("count", String.valueOf(authService.authenticatedCount())));
            langConfig.send(sender, "stats-premium", Placeholder.unparsed("count", String.valueOf(stats.premiumAccounts())));
            langConfig.send(sender, "stats-sessions-active", Placeholder.unparsed("count", String.valueOf(stats.activeSessions())));
        }));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("proudauth.admin.reload")) {
            langConfig.send(sender, "no-permission");
            return true;
        }

        reloadAction.run();
        langConfig.send(sender, "admin-reload");
        return true;
    }

    private List<String> filter(List<String> source, String token) {
        String lowered = token.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
