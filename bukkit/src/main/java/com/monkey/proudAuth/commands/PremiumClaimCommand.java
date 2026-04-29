package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class PremiumClaimCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final IdentityClaimService identityClaimService;
    private final PremiumVerifier premiumVerifier;

    public PremiumClaimCommand(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            IdentityClaimService identityClaimService,
            PremiumVerifier premiumVerifier
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.identityClaimService = identityClaimService;
        this.premiumVerifier = premiumVerifier;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            langConfig.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("proudauth.premium")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (!identityClaimService.isLocalClaimModeEnabled()) {
            langConfig.send(player, "claim-mode-disabled");
            return true;
        }
        if (pluginConfig.settings().proxy().mode() != com.monkey.proudAuth.common.config.ProudAuthSettings.ProxyMode.VELOCITY) {
            langConfig.send(player, "claim-premium-unsupported");
            return true;
        }

        String ipAddress = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        identityClaimService.snapshot(player.getName(), ipAddress)
                .thenCompose(snapshot -> {
                    if (snapshot.finalClaim().isPresent()) {
                        return java.util.concurrent.CompletableFuture.completedFuture(snapshot.finalClaim().get().name());
                    }
                    return premiumVerifier.verify(player.getName())
                            .thenCompose(premiumCheck -> {
                                if (!premiumCheck.premium()) {
                                    return java.util.concurrent.CompletableFuture.completedFuture("NOT_PREMIUM");
                                }
                                return identityClaimService.beginPendingClaim(player.getName(), AccountType.PREMIUM, ipAddress)
                                        .thenApply(ignored -> "PENDING");
                            });
                })
                .exceptionally(exception -> "ERROR")
                .thenAccept(state -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    switch (state) {
                        case "PREMIUM" -> langConfig.send(player, "claim-already-premium");
                        case "CRACKED" -> langConfig.send(player, "claim-already-cracked");
                        case "NOT_PREMIUM" -> langConfig.send(player, "claim-premium-name-not-eligible");
                        case "ERROR" -> langConfig.send(player, "claim-premium-check-unavailable");
                        case "PENDING" -> player.kick(langConfig.message(pluginConfig.settings().premium().isProxyCustom()
                                ? "claim-premium-proxy-custom-reconnect-kick"
                                : "claim-premium-reconnect-kick"));
                        default -> langConfig.send(player, "error-generic");
                    }
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
