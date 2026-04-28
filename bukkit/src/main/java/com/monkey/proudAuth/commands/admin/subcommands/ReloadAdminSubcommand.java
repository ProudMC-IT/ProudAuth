package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import org.bukkit.command.CommandSender;

public final class ReloadAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public ReloadAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "proudauth.admin.reload";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        context.reloadAction().run();
        context.langConfig().send(sender, "admin-reload");
        return true;
    }
}
