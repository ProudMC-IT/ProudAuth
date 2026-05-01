package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.storage.IpHistoryStorage;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class HistoryAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public HistoryAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "history";
    }

    @Override
    public String permission() {
        return "proudauth.admin.history";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth history <player> [limit]");
            return true;
        }
        String username = args[0];
        int limit = args.length == 2 ? Math.max(1, Math.min(50, AdminCommandSupport.parseInt(args[1], 10))) : 10;

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().listRecentHistoryByUsername(username, limit),
                (historyEntries, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (historyEntries.isEmpty()) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <yellow>Nessuna history trovata per</yellow> <white>" + username + "</white>.");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>History</aqua> <white>" + username + "</white>");
                    for (IpHistoryStorage.HistoryEntry entry : historyEntries) {
                        AdminCommandSupport.sendMini(sender,
                                "<gray>ip:</gray> <white>" + entry.ipAddress() + "</white> "
                                        + "<gray>type:</gray> <white>" + entry.accountType() + "</white> "
                                        + "<gray>when:</gray> <white>" + AdminCommandSupport.formatInstant(entry.observedAt()) + "</white>");
                    }
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]);
        }
        if (args.length == 2) {
            return AdminCommandSupport.filter(List.of("5", "10", "20", "50"), args[1]);
        }
        return List.of();
    }
}
