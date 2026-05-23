package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorState;
import com.monkey.proudAuth.common.storage.AccountRecord;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class WipePlayerAdminSubcommand implements AdminSubcommand {

    private static final Component WIPE_KICK_MESSAGE = Component.text(
            "Il tuo account ProudAuth e stato resettato dallo staff. Rientra e registrati di nuovo con /register."
    );

    private final AdminCommandContext context;

    public WipePlayerAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "wipeplayer";
    }

    @Override
    public List<String> aliases() {
        return List.of("wipe");
    }

    @Override
    public String permission() {
        return "proudauth.admin.wipeplayer";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth wipeplayer <player>");
            return true;
        }

        String username = args[0];
        Player onlinePlayer = Bukkit.getPlayerExact(username);
        CompletableFuture<Optional<AccountRecord>> accountFuture = context.authService().findAccountByUsername(username);

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.schedulerCoordinator(),
                accountFuture.thenCompose(optionalAccount -> wipeStoredState(username, optionalAccount, onlinePlayer)),
                (result, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }

                    if (result.onlinePlayer() != null) {
                        context.schedulerCoordinator().runPlayer(result.onlinePlayer(), () -> {
                            context.playerProtection().applyProtection(result.onlinePlayer());
                            context.networkSyncService().notifyMonitorState(result.onlinePlayer(), ProudAuthMonitorState.WAITING_LOGIN);
                            context.networkSyncService().notifyAuthInvalidated(result.onlinePlayer());
                            result.onlinePlayer().kick(WIPE_KICK_MESSAGE);
                        });
                    }

                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Wipe completo eseguito per</green> <white>" + username + "</white>.");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>account_rimosso:</gray> <white>" + AdminCommandSupport.yesNo(result.accountDeleted()) + "</white> "
                                    + "<gray>trusted_ip_rimossa:</gray> <white>" + AdminCommandSupport.yesNo(result.trustedIpDeleted()) + "</white> "
                                    + "<gray>whitelist_rimossa:</gray> <white>" + result.whitelistRemoved() + "</white> "
                                    + "<gray>player_online:</gray> <white>" + AdminCommandSupport.yesNo(result.onlinePlayer() != null) + "</white>");
                }
        );
        return true;
    }

    private CompletableFuture<WipeResult> wipeStoredState(
            String username,
            Optional<AccountRecord> optionalAccount,
            Player onlinePlayer
    ) {
        String normalizedIp = onlinePlayer != null
                ? AdminCommandSupport.ipOf(onlinePlayer)
                : optionalAccount.map(AccountRecord::lastIp).orElse("unknown");

        CompletableFuture<Void> runtimeLogout = onlinePlayer == null
                ? CompletableFuture.completedFuture(null)
                : context.authService().logout(onlinePlayer.getUniqueId());
        CompletableFuture<Boolean> deleteAccount = optionalAccount.isPresent()
                ? context.storage().deleteAccount(optionalAccount.get().uuid())
                : CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> trustedIpDeleted = context.storage().deleteTrustedIp(username);
        CompletableFuture<Integer> whitelistRemoved = context.storage().clearPremiumIpWhitelist(username);
        CompletableFuture<Void> clearClaim = context.storage().deleteIdentityClaim(username);
        CompletableFuture<Void> clearPendingClaim = context.identityClaimService().clearPendingClaim(username);
        CompletableFuture<Void> clearFailures = "unknown".equalsIgnoreCase(normalizedIp)
                ? CompletableFuture.completedFuture(null)
                : context.bruteForceGuard().unbanIp(normalizedIp)
                .thenRun(() -> context.bruteForceGuard().clearFailures(normalizedIp));

        if ("unknown".equalsIgnoreCase(normalizedIp)) {
            context.bruteForceGuard().clearFailures(normalizedIp);
        }

        return CompletableFuture.allOf(
                        runtimeLogout,
                        deleteAccount,
                        trustedIpDeleted,
                        whitelistRemoved,
                        clearClaim,
                        clearPendingClaim,
                        clearFailures
                )
                .thenApply(ignored -> new WipeResult(
                        deleteAccount.join(),
                        trustedIpDeleted.join(),
                        whitelistRemoved.join(),
                        onlinePlayer
                ));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? AdminCommandSupport.playerNameSuggestions(context.storage(), args[0]) : List.of();
    }

    private record WipeResult(
            boolean accountDeleted,
            boolean trustedIpDeleted,
            int whitelistRemoved,
            Player onlinePlayer
    ) {
    }
}
