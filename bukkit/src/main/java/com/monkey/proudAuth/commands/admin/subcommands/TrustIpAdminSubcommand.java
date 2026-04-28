package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

public final class TrustIpAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public TrustIpAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "trustip";
    }

    @Override
    public String permission() {
        return "proudauth.admin.trustip";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length == 0) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth trustip <ip> <player> | /proudauth trustip clear <player>");
            return true;
        }

        if ("clear".equalsIgnoreCase(args[0])) {
            if (args.length != 2) {
                AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth trustip clear <player>");
                return true;
            }
            String username = args[1].trim();
            AdminCommandSupport.handleFuture(
                    context.plugin(),
                    context.storage().deleteTrustedIp(username),
                    (removed, exception) -> {
                        if (exception != null) {
                            context.langConfig().send(sender, "error-generic");
                            return;
                        }
                        if (!Boolean.TRUE.equals(removed)) {
                            AdminCommandSupport.sendMini(sender,
                                    "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <yellow>Nessun trusted IP presente per</yellow> <white>" + username + "</white>.");
                            return;
                        }
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Trusted IP rimosso per</green> <white>" + username + "</white>.");
                    }
            );
            return true;
        }

        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth trustip <ip> <player>");
            return true;
        }

        String ipAddress = args[0].trim();
        if (!AdminCommandSupport.isLikelyIpAddress(ipAddress)) {
            context.langConfig().send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", ipAddress));
            return true;
        }
        String username = args[1].trim();

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().setTrustedIp(username, ipAddress),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.langConfig().send(sender, "admin-trust-ip-set",
                            Placeholder.unparsed("player", username),
                            Placeholder.unparsed("ip", ipAddress));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> base = new java.util.ArrayList<>(AdminCommandSupport.onlineIps());
            base.add("clear");
            return AdminCommandSupport.filter(base, args[0]);
        }
        if (args.length == 2 && "clear".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlinePlayerNames(), args[1]);
        }
        if (args.length == 2 && !"clear".equalsIgnoreCase(args[0].toLowerCase(Locale.ROOT))) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlinePlayerNames(), args[1]);
        }
        return List.of();
    }
}
