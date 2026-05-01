package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class UnbanIpAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public UnbanIpAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "unbanip";
    }

    @Override
    public String permission() {
        return "proudauth.admin.unbanip";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth unbanip <ip>");
            return true;
        }
        if (!AdminCommandSupport.isLikelyIpAddress(args[0])) {
            context.langConfig().send(sender, "admin-invalid-ip", Placeholder.unparsed("ip", args[0]));
            return true;
        }

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.bruteForceGuard().unbanIp(args[0]),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.langConfig().send(sender, "admin-ip-unbanned", Placeholder.unparsed("ip", args[0]));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.activeBannedIpSuggestions(context.storage(), args[0]) : List.of();
    }
}
