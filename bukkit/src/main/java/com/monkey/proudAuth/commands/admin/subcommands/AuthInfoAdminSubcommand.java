package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthPlayer;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.IdentityClaimRecord;
import com.monkey.proudAuth.common.storage.PendingIdentityClaimRecord;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class AuthInfoAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public AuthInfoAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "authinfo";
    }

    @Override
    public List<String> aliases() {
        return List.of("whois");
    }

    @Override
    public String permission() {
        return "proudauth.admin.authinfo";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length != 1) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth authinfo <player>");
            return true;
        }
        String username = args[0];
        Player onlinePlayer = Bukkit.getPlayerExact(username);

        CompletableFuture<Optional<AccountRecord>> accountFuture = context.authService().findAccountByUsername(username);
        CompletableFuture<Optional<IdentityClaimRecord>> localClaimFuture = context.storage().findIdentityClaim(username);
        CompletableFuture<Optional<AccountType>> effectiveClaimFuture = context.identityClaimService().resolveEffectiveClaim(username);
        CompletableFuture<Optional<PendingIdentityClaimRecord>> pendingClaimFuture = context.storage().findLatestPendingIdentityClaim(username);
        CompletableFuture<Optional<String>> trustedIpFuture = context.storage().findTrustedIp(username);
        CompletableFuture<List<String>> whitelistFuture = context.storage().listPremiumIpWhitelist(username);
        CompletableFuture<List<Session>> sessionsFuture = accountFuture.thenCompose(optionalAccount ->
                optionalAccount.isPresent()
                        ? context.storage().listSessions(optionalAccount.get().uuid(), true)
                        : CompletableFuture.completedFuture(List.of()));

        AdminCommandSupport.handleFuture(
                context.plugin(),
                CompletableFuture.allOf(accountFuture, localClaimFuture, effectiveClaimFuture, pendingClaimFuture,
                        trustedIpFuture, whitelistFuture, sessionsFuture),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    Optional<AccountRecord> optionalAccount = accountFuture.join();
                    Optional<IdentityClaimRecord> localClaim = localClaimFuture.join();
                    Optional<AccountType> effectiveClaim = effectiveClaimFuture.join();
                    Optional<PendingIdentityClaimRecord> pendingClaim = pendingClaimFuture.join();
                    Optional<String> trustedIp = trustedIpFuture.join();
                    List<String> whitelist = whitelistFuture.join();
                    List<Session> sessions = sessionsFuture.join();
                    AuthPlayer authPlayer = onlinePlayer == null ? null : context.authService().player(onlinePlayer.getUniqueId()).orElse(null);

                    if (optionalAccount.isEmpty() && localClaim.isEmpty() && pendingClaim.isEmpty() && onlinePlayer == null) {
                        context.langConfig().send(sender, "admin-player-not-found", Placeholder.unparsed("player", username));
                        return;
                    }

                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Auth info</aqua> <white>" + username + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>account:</gray> <white>" + AdminCommandSupport.yesNo(optionalAccount.isPresent()) + "</white> "
                                    + "<gray>uuid:</gray> <white>" + optionalAccount.map(account -> account.uuid().toString()).orElse("-") + "</white> "
                                    + "<gray>type:</gray> <white>" + optionalAccount.map(account -> account.accountType().name()).orElse("-") + "</white> "
                                    + "<gray>registered:</gray> <white>" + AdminCommandSupport.yesNo(optionalAccount.map(account -> account.passwordHash() != null).orElse(false)) + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>claims:</gray> <white>local=" + localClaim.map(record -> record.claimType().name()).orElse("-")
                                    + "</white> <gray>effective:</gray> <white>" + effectiveClaim.map(Enum::name).orElse("-")
                                    + "</white> <gray>pending:</gray> <white>" + pendingClaim.map(record -> record.claimType().name()).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>security:</gray> <white>2fa=" + AdminCommandSupport.yesNo(optionalAccount.map(account -> account.totpSecret() != null).orElse(false))
                                    + "</white> <gray>always=" + AdminCommandSupport.yesNo(optionalAccount.map(AccountRecord::totpFlowAlways).orElse(false))
                                    + "</gray> <gray>trusted_ip:</gray> <white>" + trustedIp.map(AdminCommandSupport::nullable).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>network:</gray> <white>last_ip=" + optionalAccount.map(AccountRecord::lastIp).map(AdminCommandSupport::nullable).orElse("-")
                                    + "</white> <gray>last_login:</gray> <white>" + optionalAccount.map(AccountRecord::lastLoginAt).map(AdminCommandSupport::formatInstant).orElse("-")
                                    + "</white> <gray>whitelist_ips:</gray> <white>" + whitelist.size() + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>runtime:</gray> <white>online=" + AdminCommandSupport.yesNo(onlinePlayer != null)
                                    + "</white> <gray>auth_state:</gray> <white>" + (authPlayer == null ? "-" : authPlayer.authState())
                                    + "</white> <gray>protected:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.playerProtection().isProtected(onlinePlayer.getUniqueId())))
                                    + "</white> <gray>claim_choice:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.playerProtection().isInClaimChoicePhase(onlinePlayer.getUniqueId())))
                                    + "</white> <gray>sessions:</gray> <white>" + sessions.size() + "</white>");
                }
        );
        return true;
    }
}
