package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class WhitelistIpAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public WhitelistIpAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "whitelistip";
    }

    @Override
    public String permission() {
        return "proudauth.admin.whitelistip";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth whitelistip <add|remove|list|clear> <player> [ip]");
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "add" -> handleAdd(sender, args);
            case "remove" -> handleRemove(sender, args);
            case "list" -> handleList(sender, args);
            case "clear" -> handleClear(sender, args);
            default -> {
                context.langConfig().send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handleAdd(CommandSender sender, String[] args) {
        if (args.length != 3) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth whitelistip add <player> <ip>");
            return true;
        }
        String username = args[1];
        String ip = args[2].trim();
        if (!AdminCommandSupport.isLikelyIpAddress(ip)) {
            context.langConfig().send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", ip));
            return true;
        }
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                context.storage().addPremiumIpWhitelist(username, ip),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.langConfig().send(sender, "admin-whitelist-ip-added",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ip", ip));
                }
        );
        return true;
    }

    private boolean handleRemove(CommandSender sender, String[] args) {
        if (args.length != 3) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth whitelistip remove <player> <ip>");
            return true;
        }
        String username = args[1];
        String ip = args[2].trim();
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                context.storage().removePremiumIpWhitelist(username, ip),
                (removed, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (!Boolean.TRUE.equals(removed)) {
                        context.langConfig().send(sender, "admin-whitelist-ip-not-found",
                                Placeholder.unparsed("player", username),
                                Placeholder.unparsed("ip", ip));
                        return;
                    }
                    context.langConfig().send(sender, "admin-whitelist-ip-removed",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ip", ip));
                }
        );
        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth whitelistip list <player>");
            return true;
        }
        String username = args[1];
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                context.storage().listPremiumIpWhitelist(username),
                (ips, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (ips.isEmpty()) {
                        context.langConfig().send(sender, "admin-whitelist-ip-empty", Placeholder.unparsed("player", username));
                        return;
                    }
                    context.langConfig().send(sender, "admin-whitelist-ip-list",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ips", String.join(", ", ips)));
                }
        );
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth whitelistip clear <player>");
            return true;
        }
        String username = args[1];
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                context.storage().clearPremiumIpWhitelist(username),
                (removedCount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Whitelist premium pulita per</green> <white>"
                                    + username + "</white> <gray>(rimossi:</gray> <white>" + removedCount + "</white><gray>)</gray>.");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("add", "remove", "list", "clear"), args[0]);
        }
        if (args.length == 2) {
            return AdminCommandSupport.playerNameSuggestions(context.storage(), args[1]);
        }
        if (args.length == 3 && "add".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlineIps(), args[2]);
        }
        if (args.length == 3 && "remove".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.premiumWhitelistIpSuggestions(context.storage(), args[1], args[2]);
        }
        return List.of();
    }
}
