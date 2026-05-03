package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class ForceLoginAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public ForceLoginAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "forcelogin";
    }

    @Override
    public String permission() {
        return "proudauth.admin.forcelogin";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth forcelogin <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            context.langConfig().send(sender, "admin-player-not-found", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", args[0]));
            return true;
        }

        AccountType accountType = context.authService()
                .player(target.getUniqueId())
                .map(auth -> auth.accountType())
                .orElse(AccountType.CRACKED);
        String ip = AdminCommandSupport.ipOf(target);

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().autoAuthenticate(target.getUniqueId(), target.getName(), accountType, AuthState.AUTHENTICATED, ip),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.playerProtection().removeProtection(target);
                    context.networkSyncService().notifyAuthCompleted(target);
                    context.langConfig().send(sender, "admin-force-login",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", target.getName()));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]) : List.of();
    }
}
