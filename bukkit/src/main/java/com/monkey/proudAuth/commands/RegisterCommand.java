package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.config.LangConfig;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class RegisterCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final AuthService authService;

    public RegisterCommand(JavaPlugin plugin, LangConfig langConfig, AuthService authService) {
        this.plugin = plugin;
        this.langConfig = langConfig;
        this.authService = authService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            langConfig.send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission("proudauth.register")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/register <password> <conferma>"));
            return true;
        }

        String ipAddress = player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
        AccountType accountType = authService.player(player.getUniqueId()).map(authPlayer -> authPlayer.accountType()).orElse(AccountType.CRACKED);
        authService.register(player.getUniqueId(), player.getName(), accountType, ipAddress, args[0], args[1]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }

            switch (result.status()) {
                case SUCCESS -> langConfig.send(player, "register-success");
                case ALREADY_REGISTERED -> langConfig.send(player, "register-already-exists");
                case PREMIUM_ACCOUNT -> langConfig.send(player, "register-premium-account");
                case MOJANG_UNAVAILABLE -> langConfig.send(player, "register-mojang-unavailable");
                case PASSWORD_MISMATCH -> langConfig.send(player, "register-password-mismatch");
                case PASSWORD_TOO_SHORT -> langConfig.send(
                        player,
                        "register-password-too-short",
                        Placeholder.unparsed("min", String.valueOf(plugin.getConfig().getInt("password.min-length", 6)))
                );
                case PASSWORD_TOO_LONG -> langConfig.send(
                        player,
                        "register-password-too-long",
                        Placeholder.unparsed("max", String.valueOf(plugin.getConfig().getInt("password.max-length", 32)))
                );
                case PASSWORD_INVALID -> langConfig.send(player, "register-password-invalid");
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
