package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class ForceLogoutAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public ForceLogoutAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "forcelogout";
    }

    @Override
    public String permission() {
        return "proudauth.admin.forcelogout";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth forcelogout <player>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            context.langConfig().send(sender, "admin-player-not-found", Placeholder.unparsed("player", args[0]));
            return true;
        }

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().logout(target.getUniqueId()),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    context.playerProtection().applyProtection(target);
                    context.langConfig().send(sender, "admin-force-logout", Placeholder.unparsed("player", target.getName()));
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]) : List.of();
    }
}
