package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SpCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final IdentityClaimService identityClaimService;
    private final PlayerProtection playerProtection;

    public SpCommand(
            JavaPlugin plugin,
            LangConfig langConfig,
            IdentityClaimService identityClaimService,
            PlayerProtection playerProtection
    ) {
        this.plugin = plugin;
        this.langConfig = langConfig;
        this.identityClaimService = identityClaimService;
        this.playerProtection = playerProtection;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            langConfig.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("proudauth.sp")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (!identityClaimService.isLocalClaimModeEnabled()) {
            langConfig.send(player, "claim-mode-disabled");
            return true;
        }

        String ipAddress = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        identityClaimService.snapshot(player.getName(), ipAddress)
                .thenCompose(snapshot -> {
                    if (snapshot.finalClaim().isPresent()) {
                        return java.util.concurrent.CompletableFuture.completedFuture(snapshot);
                    }
                    return identityClaimService.beginPendingClaim(player.getName(), AccountType.CRACKED, ipAddress)
                            .thenApply(ignored -> snapshot);
                })
                .whenComplete((snapshot, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (exception != null) {
                        langConfig.send(player, "error-generic");
                        return;
                    }
                    if (snapshot.finalClaim().orElse(null) == AccountType.PREMIUM) {
                        langConfig.send(player, "claim-already-premium");
                        return;
                    }
                    if (snapshot.finalClaim().orElse(null) == AccountType.CRACKED) {
                        langConfig.send(player, "claim-already-cracked");
                        return;
                    }
                    playerProtection.exitClaimChoicePhase(player);
                    langConfig.send(player, "claim-sp-selected");
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
