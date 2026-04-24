package com.monkey.proudAuth.commands;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Formatter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TwoFactorCommand implements CommandExecutor, TabCompleter {

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
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa <init|setup|disable|flow|status|codice>"));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1 && isNumericCode(subcommand)) {
            return handleTotpSubmission(player, subcommand);
        }

        return switch (subcommand) {
            case "init" -> handleTotpInit(player, args);
            case "setup" -> handleTotpSetup(player, args);
            case "disable" -> handleTotpDisable(player, args);
            case "flow" -> handleTotpFlow(player, args);
            case "status" -> handleTotpStatus(player, args);
            case "code", "verify" -> handleTotpCodeSubcommand(player, args);
            default -> handleUnknownUsage(player);
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("proudauth.2fa")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("init", "setup", "disable", "flow", "status", "code"), args[0]);
        }
        if (args.length == 2 && "flow".equalsIgnoreCase(args[0])) {
            return filter(List.of("true", "false"), args[1]);
        }
        return List.of();
    }

    private boolean handleTotpInit(Player player, String[] args) {
        if (args.length != 1) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa init"));
            return true;
        }
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }

        authService.initTotpSetup(player.getUniqueId(), player.getName()).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }
            switch (result.status()) {
                case SUCCESS -> {
                    long remainingSeconds = Math.max(1L, Duration.between(Instant.now(), Instant.ofEpochSecond(result.expiresAtEpochSecond())).getSeconds());
                    long remainingMinutes = Math.max(1L, (long) Math.ceil(remainingSeconds / 60.0D));
                    langConfig.send(
                            player,
                            "totp-setup-init",
                            Placeholder.unparsed("uri", result.uri()),
                            Placeholder.unparsed("key", result.secret()),
                            Placeholder.unparsed("qr_url", result.qrCodeUrl()),
                            Formatter.number("seconds", remainingSeconds),
                            Formatter.number("minutes", remainingMinutes),
                            Formatter.number("attempts", result.maxAttempts())
                    );
                    sendClickableQrLink(player, result.qrCodeUrl());
                }
                case DISABLED_IN_CONFIG -> langConfig.send(player, "totp-disabled-config");
                case ALREADY_ENABLED -> langConfig.send(player, "totp-already-enabled");
                case NOT_REGISTERED -> langConfig.send(player, "register-required-first");
            }
        }));
        return true;
    }

    private boolean handleTotpSetup(Player player, String[] args) {
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa setup <codice>"));
            return true;
        }

        authService.confirmTotpSetup(player.getUniqueId(), args[1]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }
            switch (result.status()) {
                case SUCCESS -> langConfig.send(player, "totp-setup-complete");
                case INVALID -> {
                    long remainingSeconds = Math.max(1L, Duration.between(Instant.now(), Instant.ofEpochSecond(result.expiresAtEpochSecond())).getSeconds());
                    langConfig.send(
                            player,
                            "totp-setup-code-invalid",
                            Formatter.number("attempts", result.attemptsRemaining()),
                            Formatter.number("seconds", remainingSeconds)
                    );
                }
                case SETUP_NOT_INITIALIZED -> langConfig.send(player, "totp-setup-not-initialized");
                case SETUP_EXPIRED, SETUP_ATTEMPTS_EXHAUSTED -> langConfig.send(player, "totp-setup-failed");
            }
        }));
        return true;
    }

    private boolean handleTotpDisable(Player player, String[] args) {
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa disable <codice>"));
            return true;
        }

        authService.disableTotp(player.getUniqueId(), args[1]).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
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

    private boolean handleTotpFlow(Player player, String[] args) {
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }
        if (args.length != 2) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa flow <true|false>"));
            return true;
        }
        Boolean alwaysRequired = parseBoolean(args[1]);
        if (alwaysRequired == null) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa flow <true|false>"));
            return true;
        }

        authService.updateTotpFlow(player.getUniqueId(), player.getName(), alwaysRequired).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }
            switch (result.status()) {
                case SUCCESS -> langConfig.send(player, "totp-flow-updated", Placeholder.unparsed("value", String.valueOf(result.alwaysRequired())));
                case NOT_ENABLED -> langConfig.send(player, "totp-not-enabled");
                case NOT_REGISTERED -> langConfig.send(player, "register-required-first");
            }
        }));
        return true;
    }

    private boolean handleTotpStatus(Player player, String[] args) {
        if (!authService.isAuthenticated(player.getUniqueId())) {
            langConfig.send(player, "not-authenticated");
            return true;
        }
        if (args.length != 1) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa status"));
            return true;
        }

        authService.fetchTotpFlow(player.getUniqueId(), player.getName()).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (exception != null) {
                langConfig.send(player, "error-generic");
                return;
            }
            switch (result.status()) {
                case SUCCESS -> langConfig.send(player, "totp-flow-status", Placeholder.unparsed("value", String.valueOf(result.alwaysRequired())));
                case NOT_ENABLED -> langConfig.send(player, "totp-not-enabled");
                case NOT_REGISTERED -> langConfig.send(player, "register-required-first");
            }
        }));
        return true;
    }

    private boolean handleTotpCodeSubcommand(Player player, String[] args) {
        if (args.length != 2) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa code <codice>"));
            return true;
        }
        return handleTotpSubmission(player, args[1]);
    }

    private boolean handleTotpSubmission(Player player, String code) {
        if (code.isBlank()) {
            langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa <codice>"));
            return true;
        }

        authService.submitTotp(player.getUniqueId(), player.getName(), code).whenComplete((result, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
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
                case INVALID -> langConfig.send(player, "totp-invalid");
                case NOT_REQUIRED -> langConfig.send(player, "totp-not-required");
            }
        }));
        return true;
    }

    private boolean handleUnknownUsage(Player player) {
        langConfig.send(player, "error-usage", Placeholder.unparsed("usage", "/2fa <init|setup|disable|flow|status|codice>"));
        return true;
    }

    private List<String> filter(List<String> source, String token) {
        String lowered = token.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                result.add(value);
            }
        }
        return result;
    }

    private Boolean parseBoolean(String raw) {
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "true", "on", "yes", "y", "1" -> true;
            case "false", "off", "no", "n", "0" -> false;
            default -> null;
        };
    }

    private boolean isNumericCode(String raw) {
        if (raw == null || raw.length() < 4 || raw.length() > 10) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            if (!Character.isDigit(raw.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void sendClickableQrLink(Player player, String qrCodeUrl) {
        if (!isSafeOpenUrl(qrCodeUrl)) {
            return;
        }

        Component hover = Component.text("Click to open the QR code", NamedTextColor.GRAY)
                .append(Component.newline())
                .append(Component.text(qrCodeUrl, NamedTextColor.DARK_GRAY));
        Component message = Component.text("Open QR code", NamedTextColor.AQUA)
                .decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.openUrl(qrCodeUrl))
                .hoverEvent(HoverEvent.showText(hover));
        player.sendMessage(message);
    }

    private boolean isSafeOpenUrl(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return (scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
}
