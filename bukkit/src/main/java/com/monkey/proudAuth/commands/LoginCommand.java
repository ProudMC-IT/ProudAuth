package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.network.BackendNetworkSyncService;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class LoginCommand implements CommandExecutor, TabCompleter {

    private static final Object CLAIM_REQUIRED = new Object();
    private static final Object PREMIUM_RECONNECT_REQUIRED = new Object();

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final PlayerProtection playerProtection;
    private final IdentityClaimService identityClaimService;
    private final BackendNetworkSyncService networkSyncService;

    public LoginCommand(
            JavaPlugin plugin,
            LangConfig langConfig,
            AuthService authService,
            PlayerProtection playerProtection,
            IdentityClaimService identityClaimService,
            BackendNetworkSyncService networkSyncService
    ) {
        this.plugin = plugin;
        this.langConfig = langConfig;
        this.authService = authService;
        this.playerProtection = playerProtection;
        this.identityClaimService = identityClaimService;
        this.networkSyncService = networkSyncService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            langConfig.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("proudauth.login")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (args.length != 1) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/login <password>"));
            return true;
        }
        if (!networkSyncService.allowsManualAuthentication(player)) {
            langConfig.send(player, "auth-server-required");
            return true;
        }

        String ipAddress = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        identityClaimService.snapshot(player.getName(), ipAddress)
                .thenCompose(snapshot -> {
                    if (identityClaimService.isLocalClaimModeEnabled() && snapshot.finalClaim().isEmpty()) {
                        if (identityClaimService.isAutomaticClaimOnFirstJoinEnabled()) {
                            return authService.login(player.getUniqueId(), player.getName(), ipAddress, args[0])
                                    .thenCompose(result -> (result.status() == AuthService.LoginStatus.SUCCESS
                                            || result.status() == AuthService.LoginStatus.TOTP_REQUIRED)
                                            ? identityClaimService.finalizeClaim(player.getName(), AccountType.CRACKED, ipAddress)
                                            .thenApply(ignored -> (Object) result)
                                            : java.util.concurrent.CompletableFuture.completedFuture((Object) result));
                        }
                        if (snapshot.pendingClaim().orElse(null) == AccountType.CRACKED) {
                            return authService.login(player.getUniqueId(), player.getName(), ipAddress, args[0])
                                    .thenCompose(result -> (result.status() == AuthService.LoginStatus.SUCCESS
                                            || result.status() == AuthService.LoginStatus.TOTP_REQUIRED)
                                            ? identityClaimService.finalizeClaim(player.getName(), AccountType.CRACKED, ipAddress)
                                            .thenApply(ignored -> (Object) result)
                                            : java.util.concurrent.CompletableFuture.completedFuture((Object) result));
                        }
                        return java.util.concurrent.CompletableFuture.completedFuture(
                                snapshot.pendingClaim().orElse(null) == AccountType.PREMIUM
                                        ? PREMIUM_RECONNECT_REQUIRED
                                        : CLAIM_REQUIRED
                        );
                    }
                    return authService.login(player.getUniqueId(), player.getName(), ipAddress, args[0])
                            .thenApply(result -> (Object) result);
                })
                .whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }
            if (result == CLAIM_REQUIRED) {
                langConfig.send(player, "claim-choice-required");
                return;
            }
            if (result == PREMIUM_RECONNECT_REQUIRED) {
                langConfig.send(player, "claim-premium-reconnect-required");
                return;
            }

            AuthService.LoginResult loginResult = (AuthService.LoginResult) result;

            switch (loginResult.status()) {
                case SUCCESS -> {
                    playerProtection.removeProtection(player);
                    networkSyncService.notifyAuthCompleted(player);
                    langConfig.send(player, "login-success", Placeholder.unparsed("player", player.getName()));
                }
                case ALREADY_AUTHENTICATED -> langConfig.send(player, "already-authenticated");
                case PREMIUM_ACCOUNT -> langConfig.send(player, "login-premium-account");
                case NEEDS_REGISTER -> langConfig.send(player, "register-required-first");
                case WRONG_PASSWORD -> {
                    langConfig.send(player, "login-wrong-password");
                    langConfig.send(
                            player,
                            "bruteforce-warning",
                            Placeholder.unparsed("attempts", String.valueOf(loginResult.attempts())),
                            Placeholder.unparsed("max", String.valueOf(loginResult.maxAttempts()))
                    );
                }
                case LOCKED -> {
                    langConfig.send(
                            player,
                            "bruteforce-locked",
                            Placeholder.unparsed("seconds", String.valueOf(loginResult.remainingSeconds()))
                    );
                    player.kick(langConfig.message("kick-ip-banned"));
                }
                case TOTP_REQUIRED -> langConfig.send(player, "totp-required");
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
