package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ResetPasswordAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public ResetPasswordAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "resetpassword";
    }

    @Override
    public String permission() {
        return "proudauth.admin.resetpassword";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth resetpassword <player> <nuova>");
            return true;
        }

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().resetPassword(args[0], args[1]),
                (updated, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (!Boolean.TRUE.equals(updated)) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <red>Password non resettata: account inesistente oppure premium.</red>");
                        return;
                    }
                    context.langConfig().send(sender, "admin-reset-password", Placeholder.unparsed("player", args[0]));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]) : List.of();
    }
}
