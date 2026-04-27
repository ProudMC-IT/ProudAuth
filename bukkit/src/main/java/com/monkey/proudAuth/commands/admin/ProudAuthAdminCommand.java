package com.monkey.proudAuth.commands.admin;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.storage.StorageProvider;
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
    private final StorageProvider storage;
    private final Runnable reloadAction;

    public ProudAuthAdminCommand(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            PlayerProtection playerProtection,
            BruteForceGuard bruteForceGuard,
            StorageProvider storage,
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
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth <forcelogin|forcelogout|resetpassword|banip|unbanip|trustip|whitelistip|stats|reload>"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "forcelogin" -> handleForceLogin(sender, args);
            case "forcelogout" -> handleForceLogout(sender, args);
            case "resetpassword" -> handleResetPassword(sender, args);
            case "banip" -> handleBanIp(sender, args);
            case "unbanip" -> handleUnbanIp(sender, args);
            case "trustip" -> handleTrustIp(sender, args);
            case "whitelistip" -> handleWhitelistIp(sender, args);
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
            return filter(List.of("forcelogin", "forcelogout", "resetpassword", "banip", "unbanip", "trustip", "whitelistip", "stats", "reload"), args[0]);
        }
        if (args.length == 2 && ("forcelogin".equalsIgnoreCase(args[0]) || "forcelogout".equalsIgnoreCase(args[0]))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && "resetpassword".equalsIgnoreCase(args[0])) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && "trustip".equalsIgnoreCase(args[0])) {
            return filter(
                    Bukkit.getOnlinePlayers().stream()
                            .map(player -> player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress())
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .toList(),
                    args[1]
            );
        }
        if (args.length == 2 && ("banip".equalsIgnoreCase(args[0]) || "unbanip".equalsIgnoreCase(args[0]))) {
            return filter(
                    Bukkit.getOnlinePlayers().stream()
                            .map(player -> player.getAddress() == null ? null : player.getAddress().getAddress().getHostAddress())
                            .filter(java.util.Objects::nonNull)
                            .distinct()
                            .toList(),
                    args[1]
            );
        }
        if (args.length == 3 && "banip".equalsIgnoreCase(args[0])) {
            return filter(List.of("300", "900", "1800", "3600", "7200"), args[2]);
        }
        if (args.length == 3 && "trustip".equalsIgnoreCase(args[0])) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 2 && "whitelistip".equalsIgnoreCase(args[0])) {
            return filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 3 && "whitelistip".equalsIgnoreCase(args[0])) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
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

    private boolean handleTrustIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.trustip")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length != 3) {
            langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", "/proudauth trustip <ip> <player>"));
            return true;
        }

        String ipAddress = args[1].trim();
        if (!isLikelyIpAddress(ipAddress)) {
            langConfig.send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", ipAddress));
            return true;
        }

        String username = args[2].trim();
        storage.setTrustedIp(username, ipAddress).whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(sender, "error-generic");
                return;
            }
            langConfig.send(
                    sender,
                    "admin-trust-ip-set",
                    Placeholder.unparsed("player", username),
                    Placeholder.unparsed("ip", ipAddress)
            );
        }));
        return true;
    }

    private boolean handleWhitelistIp(CommandSender sender, String[] args) {
        if (!sender.hasPermission("proudauth.admin.whitelistip")) {
            langConfig.send(sender, "no-permission");
            return true;
        }
        if (args.length < 2) {
            langConfig.send(sender, "error-usage",
                    Placeholder.unparsed("usage", "/proudauth whitelistip <add|remove|list> <player> [ip]"));
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "add" -> handleWhitelistIpAdd(sender, args);
            case "remove" -> handleWhitelistIpRemove(sender, args);
            case "list" -> handleWhitelistIpList(sender, args);
            default -> {
                langConfig.send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handleWhitelistIpAdd(CommandSender sender, String[] args) {
        if (args.length != 4) {
            langConfig.send(sender, "error-usage",
                    Placeholder.unparsed("usage", "/proudauth whitelistip add <player> <ip>"));
            return true;
        }
        String username = args[2];
        String ip = args[3].trim();
        if (!isLikelyIpAddress(ip)) {
            langConfig.send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", ip));
            return true;
        }
        storage.addPremiumIpWhitelist(username, ip).whenComplete((ignored, exception) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (exception != null) {
                        langConfig.send(sender, "error-generic");
                        return;
                    }
                    langConfig.send(sender, "admin-whitelist-ip-added",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ip", ip));
                }));
        return true;
    }

    private boolean handleWhitelistIpRemove(CommandSender sender, String[] args) {
        if (args.length != 4) {
            langConfig.send(sender, "error-usage",
                    Placeholder.unparsed("usage", "/proudauth whitelistip remove <player> <ip>"));
            return true;
        }
        String username = args[2];
        String ip = args[3].trim();
        storage.removePremiumIpWhitelist(username, ip).whenComplete((removed, exception) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (exception != null) {
                        langConfig.send(sender, "error-generic");
                        return;
                    }
                    if (!removed) {
                        langConfig.send(sender, "admin-whitelist-ip-not-found",
                                Placeholder.unparsed("player", username),
                                Placeholder.unparsed("ip", ip));
                        return;
                    }
                    langConfig.send(sender, "admin-whitelist-ip-removed",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ip", ip));
                }));
        return true;
    }

    private boolean handleWhitelistIpList(CommandSender sender, String[] args) {
        if (args.length != 3) {
            langConfig.send(sender, "error-usage",
                    Placeholder.unparsed("usage", "/proudauth whitelistip list <player>"));
            return true;
        }
        String username = args[2];
        storage.listPremiumIpWhitelist(username).whenComplete((ips, exception) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (exception != null) {
                        langConfig.send(sender, "error-generic");
                        return;
                    }
                    if (ips.isEmpty()) {
                        langConfig.send(sender, "admin-whitelist-ip-empty",
                                Placeholder.unparsed("player", username));
                        return;
                    }
                    langConfig.send(sender, "admin-whitelist-ip-list",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ips", String.join(", ", ips)));
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

    private boolean isLikelyIpAddress(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 45) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (Character.isDigit(character)
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == '.'
                    || character == ':') {
                continue;
            }
            return false;
        }
        return true;
    }
}
