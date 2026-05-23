package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class UnlockAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public UnlockAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "unlock";
    }

    @Override
    public String permission() {
        return "proudauth.admin.unlock";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth unlock <player>");
            return true;
        }

        String username = args[0];
        Player onlinePlayer = Bukkit.getPlayerExact(username);
        CompletableFuture<java.util.Optional<com.monkey.proudAuth.common.storage.AccountRecord>> accountFuture =
                context.authService().findAccountByUsername(username);

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                accountFuture.thenCompose(optionalAccount -> {
                    String ipAddress = onlinePlayer != null
                            ? AdminCommandSupport.ipOf(onlinePlayer)
                            : optionalAccount.map(com.monkey.proudAuth.common.storage.AccountRecord::lastIp).orElse("unknown");
                    CompletableFuture<Void> unbanFuture = "unknown".equalsIgnoreCase(ipAddress)
                            ? CompletableFuture.completedFuture(null)
                            : context.bruteForceGuard().unbanIp(ipAddress);
                    context.bruteForceGuard().clearFailures(ipAddress);
                    return CompletableFuture.allOf(
                            unbanFuture,
                            context.identityClaimService().clearPendingClaim(username)
                    ).thenApply(ignored -> ipAddress);
                }),
                (ipAddress, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Sblocco completato per</green> <white>" + username + "</white>"
                                    + " <gray>(ip:</gray> <white>" + AdminCommandSupport.nullable(ipAddress) + "</white><gray>)</gray>.");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]) : List.of();
    }
}
