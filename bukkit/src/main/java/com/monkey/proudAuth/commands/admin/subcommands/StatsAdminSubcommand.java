package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

public final class StatsAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public StatsAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String permission() {
        return "proudauth.admin.stats";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().fetchStats(),
                (stats, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.langConfig().send(sender, "stats-header");
                    context.langConfig().send(sender, "stats-registered", Placeholder.unparsed("count", String.valueOf(stats.registeredAccounts())));
                    context.langConfig().send(sender, "stats-online", Placeholder.unparsed("count", String.valueOf(context.authService().authenticatedCount())));
                    context.langConfig().send(sender, "stats-premium", Placeholder.unparsed("count", String.valueOf(stats.premiumAccounts())));
                    context.langConfig().send(sender, "stats-sessions-active", Placeholder.unparsed("count", String.valueOf(stats.activeSessions())));
                }
        );
        return true;
    }
}
