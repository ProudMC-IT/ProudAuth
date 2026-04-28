package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.storage.AccountRecord;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class TwoFactorAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public TwoFactorAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "2fa";
    }

    @Override
    public String permission() {
        return "proudauth.admin.2fa";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth 2fa <status|reset> <player>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> handleStatus(sender, args[1]);
            case "reset" -> handleReset(sender, args[1]);
            default -> {
                context.langConfig().send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender, String username) {
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().findAccountByUsername(username),
                (optionalAccount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (optionalAccount.isEmpty()) {
                        context.langConfig().send(sender, "admin-player-not-found",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", username));
                        return;
                    }
                    AccountRecord account = optionalAccount.get();
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>2FA status</aqua> <white>" + username + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>enabled:</gray> <white>" + AdminCommandSupport.yesNo(account.totpSecret() != null) + "</white> "
                                    + "<gray>flow_always:</gray> <white>" + AdminCommandSupport.yesNo(account.totpFlowAlways()) + "</white> "
                                    + "<gray>registered:</gray> <white>" + AdminCommandSupport.yesNo(account.passwordHash() != null) + "</white>");
                }
        );
        return true;
    }

    private boolean handleReset(CommandSender sender, String username) {
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().findAccountByUsername(username)
                        .thenCompose(optionalAccount -> optionalAccount.isEmpty()
                                ? CompletableFuture.completedFuture(Optional.<AccountRecord>empty())
                                : context.storage().updateTotpSecret(optionalAccount.get().uuid(), null)
                                .thenCompose(ignored -> context.storage().updateTotpFlow(
                                        optionalAccount.get().uuid(),
                                        context.pluginConfig().settings().security().totpDefaultFlowAlways()))
                                .thenApply(ignored -> optionalAccount)),
                (optionalAccount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (optionalAccount.isEmpty()) {
                        context.langConfig().send(sender, "admin-player-not-found",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", username));
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>2FA resettata per</green> <white>" + username + "</white>.");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("status", "reset"), args[0]);
        }
        if (args.length == 2) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlinePlayerNames(), args[1]);
        }
        return List.of();
    }
}
