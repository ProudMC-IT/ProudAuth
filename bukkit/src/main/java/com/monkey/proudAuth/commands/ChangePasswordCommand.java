package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
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

public final class ChangePasswordCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final AuthService authService;

    public ChangePasswordCommand(JavaPlugin plugin, LangConfig langConfig, AuthService authService) {
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
        if (!player.hasPermission("proudauth.changepassword")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }
        if (args.length != 3) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/changepassword <vecchia> <nuova> <conferma>"));
            return true;
        }

        authService.changePassword(player.getUniqueId(), player.getName(), args[0], args[1], args[2]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }

            switch (result.status()) {
                case SUCCESS -> langConfig.send(player, "change-password-success");
                case WRONG_OLD_PASSWORD -> langConfig.send(player, "change-password-wrong-old");
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
                case NOT_REGISTERED -> langConfig.send(player, "register-required-first");
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
