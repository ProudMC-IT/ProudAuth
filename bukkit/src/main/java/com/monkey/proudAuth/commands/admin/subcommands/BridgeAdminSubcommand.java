package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.List;

public final class BridgeAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public BridgeAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "bridge";
    }

    @Override
    public String permission() {
        return "proudauth.admin.bridge";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 2 || !"status".equalsIgnoreCase(args[0])) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth bridge status <player>");
            return true;
        }

        String username = args[1];
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().findLatestProxyAssertionByUsername(username),
                (optionalAssertion, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Bridge status</aqua> <white>" + username + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>enabled:</gray> <white>" + AdminCommandSupport.yesNo(context.pluginConfig().settings().bridge().enabled()) + "</white> "
                                    + "<gray>mode:</gray> <white>" + context.pluginConfig().settings().bridge().mode() + "</white> "
                                    + "<gray>transport:</gray> <white>" + context.pluginConfig().settings().bridge().transport() + "</white>");
                    if (optionalAssertion.isEmpty()) {
                        AdminCommandSupport.sendMini(sender, "<gray>latest_assertion:</gray> <white>-</white>");
                        return;
                    }
                    ProxyBridgeAssertion assertion = optionalAssertion.get();
                    AdminCommandSupport.sendMini(sender,
                            "<gray>latest_assertion:</gray> <white>active=" + AdminCommandSupport.yesNo(assertion.expiresAt().isAfter(Instant.now()))
                                    + "</white> <gray>type:</gray> <white>" + assertion.accountType()
                                    + "</white> <gray>uuid:</gray> <white>" + assertion.uuid() + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>resolved_name:</gray> <white>" + assertion.resolvedName() + "</white> "
                                    + "<gray>ip:</gray> <white>" + assertion.ipAddress() + "</white> "
                                    + "<gray>issued:</gray> <white>" + AdminCommandSupport.formatInstant(assertion.issuedAt()) + "</white> "
                                    + "<gray>expires:</gray> <white>" + AdminCommandSupport.formatInstant(assertion.expiresAt()) + "</white>");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("status"), args[0]);
        }
        if (args.length == 2 && "status".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlinePlayerNames(), args[1]);
        }
        return List.of();
    }
}
