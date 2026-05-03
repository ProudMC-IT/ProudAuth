package com.monkey.proudAuth.commands.admin.support;

import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.config.LangConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public final class AdminCommandSupport {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int TAB_DB_LIMIT = 60;
    private static final long TAB_DB_TIMEOUT_MS = 150L;

    private AdminCommandSupport() {
    }

    public static boolean ensurePermission(CommandSender sender, String permission, LangConfig langConfig) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        langConfig.send(sender, "no-permission");
        return false;
    }

    public static void sendUsage(CommandSender sender, LangConfig langConfig, String usage) {
        langConfig.send(sender, "error-usage", Placeholder.unparsed("usage", usage));
    }

    public static void sendMini(CommandSender sender, String miniMessage) {
        sender.sendMessage(MINI_MESSAGE.deserialize(miniMessage));
    }

    public static List<String> filter(Collection<String> source, String token) {
        String lowered = token == null ? "" : token.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    public static List<String> onlinePlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static List<String> onlineIps() {
        return Bukkit.getOnlinePlayers().stream()
                .map(AdminCommandSupport::ipOf)
                .filter(ip -> !"unknown".equalsIgnoreCase(ip))
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public static List<String> playerNameSuggestions(StorageProvider storage, String token) {
        LinkedHashSet<String> suggestions = new LinkedHashSet<>(onlinePlayerNames());
        suggestions.addAll(awaitSuggestions(storage.listAccountUsernames(emptyIfNull(token), TAB_DB_LIMIT)));
        return filter(suggestions, token);
    }

    public static List<String> activeBannedIpSuggestions(StorageProvider storage, String token) {
        return filter(awaitSuggestions(storage.listActiveBannedIps(emptyIfNull(token), TAB_DB_LIMIT)), token);
    }

    public static List<String> premiumWhitelistIpSuggestions(StorageProvider storage, String username, String token) {
        if (username == null || username.isBlank()) {
            return filter(onlineIps(), token);
        }
        return filter(awaitSuggestions(storage.listPremiumIpWhitelist(username)), token);
    }

    public static List<String> onlineIpsWithKeyword(String keyword, String token) {
        List<String> suggestions = new ArrayList<>(onlineIps());
        suggestions.add(keyword);
        return filter(suggestions, token);
    }

    public static String ipOf(Player player) {
        return player.getAddress() == null || player.getAddress().getAddress() == null
                ? "unknown"
                : player.getAddress().getAddress().getHostAddress();
    }

    public static boolean isLikelyIpAddress(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 45) {
            return false;
        }
        for (int i = 0; i < raw.length(); i++) {
            char character = raw.charAt(i);
            if (Character.isDigit(character)
                    || (character >= 'a' && character <= 'f')
                    || (character >= 'A' && character <= 'F')
                    || character == '.'
                    || character == ':') {
                continue;
            }
            return false;
        }
        return true;
    }

    public static String yesNo(boolean value) {
        return value ? "si" : "no";
    }

    public static String nullable(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public static String formatInstant(Instant instant) {
        return instant == null ? "-" : DATE_TIME_FORMATTER.format(instant);
    }

    public static String formatRemaining(Instant futureInstant) {
        if (futureInstant == null) {
            return "-";
        }
        Duration remaining = Duration.between(Instant.now(), futureInstant);
        if (remaining.isNegative() || remaining.isZero()) {
            return "0s";
        }
        long seconds = remaining.getSeconds();
        long days = seconds / 86400;
        seconds %= 86400;
        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;
        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            builder.append(days).append("d ");
        }
        if (hours > 0) {
            builder.append(hours).append("h ");
        }
        if (minutes > 0) {
            builder.append(minutes).append("m ");
        }
        if (seconds > 0 || builder.isEmpty()) {
            builder.append(seconds).append("s");
        }
        return builder.toString().trim();
    }

    public static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    public static <T> void handleFuture(JavaPlugin plugin, CompletableFuture<T> future, BiConsumer<T, Throwable> consumer) {
        future.whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> consumer.accept(result, throwable)));
    }

    private static List<String> awaitSuggestions(CompletableFuture<List<String>> future) {
        try {
            return future.get(TAB_DB_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
