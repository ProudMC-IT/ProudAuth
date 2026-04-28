package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import org.bukkit.command.CommandSender;

public final class PremiumCheckAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public PremiumCheckAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "premiumcheck";
    }

    @Override
    public java.util.List<String> aliases() {
        return java.util.List.of("premium-check");
    }

    @Override
    public String permission() {
        return "proudauth.admin.premiumcheck";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth premiumcheck <player>");
            return true;
        }

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.premiumVerifier().verify(args[0]),
                (result, exception) -> {
                    if (exception != null) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <red>Verifica Mojang fallita.</red>");
                        return;
                    }
                    PremiumVerifier.PremiumCheckResult check = result;
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Premium check</aqua> <white>" + args[0] + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>premium:</gray> <white>" + (check.premium() ? "si" : "no") + "</white> "
                                    + "<gray>resolved_name:</gray> <white>" + check.resolvedName() + "</white> "
                                    + "<gray>uuid:</gray> <white>" + check.resolvedUuid() + "</white>");
                }
        );
        return true;
    }
}
