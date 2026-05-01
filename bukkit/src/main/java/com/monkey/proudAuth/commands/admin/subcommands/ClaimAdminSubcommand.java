package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.IdentityClaimRecord;
import com.monkey.proudAuth.common.storage.PendingIdentityClaimRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class ClaimAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public ClaimAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "claim";
    }

    @Override
    public String permission() {
        return "proudauth.admin.claim";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth claim <status|set|clear|pending> ...");
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> handleStatus(sender, args);
            case "set" -> handleSet(sender, args);
            case "clear" -> handleClear(sender, args);
            case "pending" -> handlePending(sender, args);
            default -> {
                context.langConfig().send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth claim status <player>");
            return true;
        }
        String username = args[1];
        CompletableFuture<Optional<IdentityClaimRecord>> localClaimFuture = context.storage().findIdentityClaim(username);
        CompletableFuture<Optional<AccountType>> effectiveClaimFuture = context.identityClaimService().resolveEffectiveClaim(username);
        CompletableFuture<Optional<PendingIdentityClaimRecord>> pendingClaimFuture = context.storage().findLatestPendingIdentityClaim(username);
        CompletableFuture<Optional<AccountRecord>> accountFuture = context.authService().findAccountByUsername(username);

        AdminCommandSupport.handleFuture(
                context.plugin(),
                CompletableFuture.allOf(localClaimFuture, effectiveClaimFuture, pendingClaimFuture, accountFuture),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    Optional<IdentityClaimRecord> localClaim = localClaimFuture.join();
                    Optional<AccountType> effectiveClaim = effectiveClaimFuture.join();
                    Optional<PendingIdentityClaimRecord> pendingClaim = pendingClaimFuture.join();
                    Optional<AccountRecord> account = accountFuture.join();

                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Claim status</aqua> <white>" + username + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>local_claim:</gray> <white>" + localClaim.map(record -> record.claimType().name()).orElse("-") + "</white> "
                                    + "<gray>effective_claim:</gray> <white>" + effectiveClaim.map(Enum::name).orElse("-") + "</white> "
                                    + "<gray>pending_claim:</gray> <white>" + pendingClaim.map(record -> record.claimType().name()).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>claim_ip:</gray> <white>" + localClaim.map(IdentityClaimRecord::claimedIp).map(AdminCommandSupport::nullable).orElse("-") + "</white> "
                                    + "<gray>claim_at:</gray> <white>" + localClaim.map(IdentityClaimRecord::claimedAt).map(AdminCommandSupport::formatInstant).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>pending_ip:</gray> <white>" + pendingClaim.map(PendingIdentityClaimRecord::ipAddress).map(AdminCommandSupport::nullable).orElse("-") + "</white> "
                                    + "<gray>pending_expires:</gray> <white>" + pendingClaim.map(PendingIdentityClaimRecord::expiresAt).map(AdminCommandSupport::formatInstant).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>account_present:</gray> <white>" + AdminCommandSupport.yesNo(account.isPresent()) + "</white> "
                                    + "<gray>account_type:</gray> <white>" + account.map(record -> record.accountType().name()).orElse("-") + "</white> "
                                    + "<gray>account_uuid:</gray> <white>" + account.map(record -> record.uuid().toString()).orElse("-") + "</white>");
                }
        );
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (args.length != 3) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth claim set <player> <premium|cracked>");
            return true;
        }
        String username = args[1];
        AccountType claimType;
        try {
            claimType = AccountType.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            AdminCommandSupport.sendMini(sender,
                    "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <red>Valore claim non valido. Usa premium oppure cracked.</red>");
            return true;
        }

        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().saveIdentityClaim(username, claimType, null, Instant.now())
                        .thenCompose(ignored -> context.identityClaimService().clearPendingClaim(username))
                        .thenCompose(ignored -> context.authService().findAccountByUsername(username))
                        .thenCompose(optionalAccount -> optionalAccount.isPresent()
                                ? context.sessionManager().invalidate(optionalAccount.get().uuid()).thenApply(x -> optionalAccount)
                                : CompletableFuture.completedFuture(optionalAccount)),
                (optionalAccount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    Player onlinePlayer = Bukkit.getPlayerExact(username);
                    if (onlinePlayer != null) {
                        if (claimType == AccountType.CRACKED) {
                            context.playerProtection().exitClaimChoicePhase(onlinePlayer);
                        } else {
                            context.playerProtection().enterClaimChoicePhase(onlinePlayer);
                        }
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Claim impostato su</green> <white>" + claimType.name()
                                    + "</white> <green>per</green> <white>" + username + "</white>.");
                    if (optionalAccount.isPresent()) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <gray>Sessioni account invalidate per riallineare il nuovo claim.</gray>");
                    }
                }
        );
        return true;
    }

    private boolean handleClear(CommandSender sender, String[] args) {
        if (args.length != 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth claim clear <player>");
            return true;
        }
        String username = args[1];
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().findAccountByUsername(username).thenCompose(optionalAccount ->
                        context.storage().deleteIdentityClaim(username)
                                .thenCompose(ignored -> context.identityClaimService().clearPendingClaim(username))
                                .thenApply(ignored -> optionalAccount)),
                (optionalAccount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    Player onlinePlayer = Bukkit.getPlayerExact(username);
                    if (onlinePlayer != null && context.playerProtection().isProtected(onlinePlayer.getUniqueId())) {
                        context.playerProtection().enterClaimChoicePhase(onlinePlayer);
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Claim locale e pending rimossi per</green> <white>" + username + "</white>.");
                    if (optionalAccount.isPresent()) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <yellow>Nota:</yellow> <gray>esiste ancora un record account; l'effective claim potrebbe essere re-inferito dai dati storici.</gray>");
                    }
                }
        );
        return true;
    }

    private boolean handlePending(CommandSender sender, String[] args) {
        if (args.length != 3 || !"clear".equalsIgnoreCase(args[1])) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth claim pending clear <player>");
            return true;
        }
        String username = args[2];
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.identityClaimService().clearPendingClaim(username),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Pending claim rimosso per</green> <white>" + username + "</white>.");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("status", "set", "clear", "pending"), args[0]);
        }
        if (args.length == 2 && ("status".equalsIgnoreCase(args[0]) || "set".equalsIgnoreCase(args[0]) || "clear".equalsIgnoreCase(args[0]))) {
            return AdminCommandSupport.playerNameSuggestions(context.storage(), args[1]);
        }
        if (args.length == 2 && "pending".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(List.of("clear"), args[1]);
        }
        if (args.length == 3 && "pending".equalsIgnoreCase(args[0]) && "clear".equalsIgnoreCase(args[1])) {
            return AdminCommandSupport.playerNameSuggestions(context.storage(), args[2]);
        }
        if (args.length == 3 && "set".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(List.of("premium", "cracked"), args[2]);
        }
        return List.of();
    }
}
