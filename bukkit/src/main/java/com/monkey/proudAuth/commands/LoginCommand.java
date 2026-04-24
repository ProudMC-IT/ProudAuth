package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.config.LangConfig;
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

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final PlayerProtection playerProtection;

    public LoginCommand(JavaPlugin plugin, LangConfig langConfig, AuthService authService, PlayerProtection playerProtection) {
        this.plugin = plugin;
        this.langConfig = langConfig;
        this.authService = authService;
        this.playerProtection = playerProtection;
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

        String ipAddress = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        authService.login(player.getUniqueId(), player.getName(), ipAddress, args[0]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }

            switch (result.status()) {
                case SUCCESS -> {
                    playerProtection.removeProtection(player);
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
                            Placeholder.unparsed("attempts", String.valueOf(result.attempts())),
                            Placeholder.unparsed("max", String.valueOf(result.maxAttempts()))
                    );
                }
                case LOCKED -> {
                    langConfig.send(
                            player,
                            "bruteforce-locked",
                            Placeholder.unparsed("seconds", String.valueOf(result.remainingSeconds()))
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
