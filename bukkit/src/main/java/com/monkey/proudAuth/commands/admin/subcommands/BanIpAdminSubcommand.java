package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class BanIpAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public BanIpAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "banip";
    }

    @Override
    public String permission() {
        return "proudauth.admin.banip";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth banip <ip> [secondi]");
            return true;
        }
        if (!AdminCommandSupport.isLikelyIpAddress(args[0])) {
            context.langConfig().send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", args[0]));
            return true;
        }

        long seconds = args.length == 2
                ? AdminCommandSupport.parseLong(args[1], context.pluginConfig().settings().security().lockoutSeconds())
                : context.pluginConfig().settings().security().lockoutSeconds();

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.bruteForceGuard().banIp(args[0], seconds, "Ban manuale admin"),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.langConfig().send(sender, "admin-ip-banned", Placeholder.unparsed("ip", args[0]));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlineIps(), args[0]);
        }
        if (args.length == 2) {
            return AdminCommandSupport.filter(List.of("300", "900", "1800", "3600", "7200"), args[1]);
        }
        return List.of();
    }
}
