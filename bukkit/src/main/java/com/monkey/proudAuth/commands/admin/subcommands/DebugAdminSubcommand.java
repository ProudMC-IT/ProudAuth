package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.model.AuthPlayer;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.IdentityClaimRecord;
import com.monkey.proudAuth.common.storage.PendingIdentityClaimRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class DebugAdminSubcommand implements AdminSubcommand {

    private static final Map<String, String> DEBUG_PATHS = createDebugPaths();

    private final AdminCommandContext context;

    public DebugAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "debug";
    }

    @Override
    public String permission() {
        return "proudauth.admin.debug";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth debug <player|toggle> ...");
            return true;
        }
        return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "player" -> handlePlayer(sender, args[1]);
            case "toggle" -> handleToggle(sender, args);
            default -> {
                context.langConfig().send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handlePlayer(CommandSender sender, String username) {
        Player onlinePlayer = Bukkit.getPlayerExact(username);
        CompletableFuture<Optional<AccountRecord>> accountFuture = context.authService().findAccountByUsername(username);
        CompletableFuture<Optional<IdentityClaimRecord>> localClaimFuture = context.storage().findIdentityClaim(username);
        CompletableFuture<Optional<PendingIdentityClaimRecord>> pendingClaimFuture = context.storage().findLatestPendingIdentityClaim(username);
        CompletableFuture<List<Session>> sessionsFuture = accountFuture.thenCompose(optionalAccount ->
                optionalAccount.isPresent()
                        ? context.storage().listSessions(optionalAccount.get().uuid(), true)
                        : CompletableFuture.completedFuture(List.of()));
        CompletableFuture<Optional<String>> trustedIpFuture = context.storage().findTrustedIp(username);

        AdminCommandSupport.handleFuture(
                context.plugin(),
                CompletableFuture.allOf(accountFuture, localClaimFuture, pendingClaimFuture, sessionsFuture, trustedIpFuture),
                (ignored, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    Optional<AccountRecord> optionalAccount = accountFuture.join();
                    Optional<IdentityClaimRecord> localClaim = localClaimFuture.join();
                    Optional<PendingIdentityClaimRecord> pendingClaim = pendingClaimFuture.join();
                    List<Session> sessions = sessionsFuture.join();
                    Optional<String> trustedIp = trustedIpFuture.join();
                    AuthPlayer authPlayer = onlinePlayer == null ? null : context.authService().player(onlinePlayer.getUniqueId()).orElse(null);

                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Debug player</aqua> <white>" + username + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>online:</gray> <white>" + AdminCommandSupport.yesNo(onlinePlayer != null) + "</white> "
                                    + "<gray>protected:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.playerProtection().isProtected(onlinePlayer.getUniqueId())))
                                    + "</white> <gray>claim_choice:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.playerProtection().isInClaimChoicePhase(onlinePlayer.getUniqueId()))) + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>tracked_auth:</gray> <white>" + (authPlayer == null ? "-" : authPlayer.authState())
                                    + "</white> <gray>authenticated:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.authService().isAuthenticated(onlinePlayer.getUniqueId())))
                                    + "</white> <gray>pending_totp:</gray> <white>" + (onlinePlayer == null ? "-" : AdminCommandSupport.yesNo(context.authService().hasPendingTotpChallenge(onlinePlayer.getUniqueId()))) + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>account:</gray> <white>" + optionalAccount.map(account -> account.uuid().toString()).orElse("-")
                                    + "</white> <gray>type:</gray> <white>" + optionalAccount.map(account -> account.accountType().name()).orElse("-")
                                    + "</white> <gray>last_ip:</gray> <white>" + optionalAccount.map(AccountRecord::lastIp).map(AdminCommandSupport::nullable).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>claims:</gray> <white>local=" + localClaim.map(record -> record.claimType().name()).orElse("-")
                                    + "</white> <gray>pending:</gray> <white>" + pendingClaim.map(record -> record.claimType().name()).orElse("-")
                                    + "</white> <gray>trusted_ip:</gray> <white>" + trustedIp.map(AdminCommandSupport::nullable).orElse("-") + "</white>");
                    AdminCommandSupport.sendMini(sender,
                            "<gray>sessions:</gray> <white>" + sessions.size() + "</white> "
                                    + "<gray>debugger:</gray> <white>" + context.pluginConfig().settings().debugger().summary() + "</white>");
                }
        );
        return true;
    }

    private boolean handleToggle(CommandSender sender, String[] args) {
        if (args.length != 3) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth debug toggle <channel> <on|off>");
            return true;
        }
        String channel = args[1].toLowerCase(java.util.Locale.ROOT);
        String path = DEBUG_PATHS.get(channel);
        if (path == null) {
            AdminCommandSupport.sendMini(sender,
                    "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <red>Canale debug non valido.</red> <gray>Valori:</gray> <white>" + String.join(", ", DEBUG_PATHS.keySet()) + "</white>");
            return true;
        }
        Boolean enabled = switch (args[2].toLowerCase(java.util.Locale.ROOT)) {
            case "on", "true", "1" -> true;
            case "off", "false", "0" -> false;
            default -> null;
        };
        if (enabled == null) {
            AdminCommandSupport.sendMini(sender,
                    "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <red>Usa on oppure off.</red>");
            return true;
        }
        context.plugin().getConfig().set(path, enabled);
        context.plugin().saveConfig();
        context.reloadAction().run();
        AdminCommandSupport.sendMini(sender,
                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Debugger aggiornato:</green> <white>" + channel
                        + "</white> <gray>=</gray> <white>" + (enabled ? "on" : "off") + "</white>.");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("player", "toggle"), args[0]);
        }
        if (args.length == 2 && "player".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlinePlayerNames(), args[1]);
        }
        if (args.length == 2 && "toggle".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(DEBUG_PATHS.keySet(), args[1]);
        }
        if (args.length == 3 && "toggle".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(List.of("on", "off"), args[2]);
        }
        return List.of();
    }

    private static Map<String, String> createDebugPaths() {
        Map<String, String> paths = new LinkedHashMap<>();
        paths.put("enabled", "debugger.enabled");
        paths.put("player-resolution", "debugger.player-resolution");
        paths.put("session-flow", "debugger.session-flow");
        paths.put("premium-flow", "debugger.premium-flow");
        paths.put("bridge-flow", "debugger.bridge-flow");
        paths.put("security-flow", "debugger.security-flow");
        paths.put("protection-flow", "debugger.protection-flow");
        paths.put("ip-ban-flow", "debugger.ip-ban-flow");
        paths.put("profile-flow", "debugger.profile-flow");
        paths.put("movement-audit", "debugger.movement-audit");
        paths.put("teleport-audit", "debugger.teleport-audit");
        paths.put("command-flow", "debugger.command-flow");
        return Map.copyOf(paths);
    }
}
