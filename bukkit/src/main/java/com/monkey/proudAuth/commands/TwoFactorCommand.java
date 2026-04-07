package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TwoFactorCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final PlayerProtection playerProtection;

    public TwoFactorCommand(JavaPlugin plugin, LangConfig langConfig, AuthService authService, PlayerProtection playerProtection) {
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
        if (!player.hasPermission("proudauth.2fa")) {
            langConfig.send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa <setup|disable|codice>"));
            return true;
        }

        if ("setup".equalsIgnoreCase(args[0])) {
            if (!authService.isAuthenticated(player.getUniqueId())) {
                langConfig.send(player, "not-authenticated");
                return true;
            }

            authService.setupTotp(player.getUniqueId(), player.getName()).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (exception != null) {
                    langConfig.send(player, "error-generic");
                    return;
                }

                switch (result.status()) {
                    case SUCCESS -> langConfig.send(
                            player,
                            "totp-setup",
                            Placeholder.unparsed("key", result.secret()),
                            Placeholder.unparsed("uri", result.uri())
                    );
                    case DISABLED_IN_CONFIG -> langConfig.send(player, "totp-disabled-config");
                    case ALREADY_ENABLED -> langConfig.send(player, "totp-already-enabled");
                    case NOT_REGISTERED -> langConfig.send(player, "register-required-first");
                }
            }));
            return true;
        }

        if ("disable".equalsIgnoreCase(args[0])) {
            if (!authService.isAuthenticated(player.getUniqueId())) {
                langConfig.send(player, "not-authenticated");
                return true;
            }
            if (args.length != 2) {
                langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa disable <codice>"));
                return true;
            }

            authService.disableTotp(player.getUniqueId(), args[1]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (exception != null) {
                    langConfig.send(player, "error-generic");
                    return;
                }

                switch (result.status()) {
                    case SUCCESS -> langConfig.send(player, "totp-disabled");
                    case INVALID -> langConfig.send(player, "totp-invalid");
                    case NOT_ENABLED -> langConfig.send(player, "totp-not-enabled");
                }
            }));
            return true;
        }

        if (args.length != 1) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa <codice>"));
            return true;
        }

        authService.submitTotp(player.getUniqueId(), player.getName(), args[0]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }

            switch (result.status()) {
                case SUCCESS -> {
                    playerProtection.removeProtection(player);
                    langConfig.send(player, "login-success", Placeholder.unparsed("player", player.getName()));
                }
                case INVALID -> langConfig.send(player, "totp-invalid");
                case NOT_REQUIRED -> langConfig.send(player, "totp-not-required");
            }
        }));
        return true;
    }
}
