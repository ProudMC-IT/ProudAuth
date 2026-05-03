package com.monkey.proudAuth.common.logging;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ProudAuthConsoleLogger {

    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_GRAY = "\u001B[90m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_MAGENTA = "\u001B[35m";
    private static final String ANSI_WHITE = "\u001B[97m";
    private static final String ANSI_BLUE = "\u001B[34m";
    private static final String ANSI_LIGHT_BLUE = "\u001B[94m";
    private static final String ANSI_LIGHT_GRAY = "\u001B[37m";
    private static final String ANSI_LIGHT_GREEN = "\u001B[92m";
    private static final String ANSI_LIGHT_MAGENTA = "\u001B[95m";
    private static final String SEPARATOR = "------------------------------------------------------------";
    private static final int EVENT_KEY_WIDTH = 22;

    private final String prefix;
    private final Consumer<String> infoConsumer;
    private final Consumer<String> warnConsumer;
    private final BiConsumer<String, Throwable> errorConsumer;
    private final boolean ansiEnabled;
    private final boolean includePrefix;

    public ProudAuthConsoleLogger(
            String name,
            Consumer<String> infoConsumer,
            Consumer<String> warnConsumer,
            BiConsumer<String, Throwable> errorConsumer
    ) {
        this(name, infoConsumer, warnConsumer, errorConsumer, true);
    }

    public ProudAuthConsoleLogger(
            String name,
            Consumer<String> infoConsumer,
            Consumer<String> warnConsumer,
            BiConsumer<String, Throwable> errorConsumer,
            boolean includePrefix
    ) {
        String baseName = Objects.requireNonNull(name, "name");
        this.prefix = "[" + baseName + "]";
        this.infoConsumer = Objects.requireNonNull(infoConsumer, "infoConsumer");
        this.warnConsumer = Objects.requireNonNull(warnConsumer, "warnConsumer");
        this.errorConsumer = Objects.requireNonNull(errorConsumer, "errorConsumer");
        this.includePrefix = includePrefix;
        this.ansiEnabled = !"true".equalsIgnoreCase(System.getProperty("proudauth.noAnsi", "false"));
    }

    public void banner(String... lines) {
        infoConsumer.accept(color(ANSI_GRAY, SEPARATOR));
        for (String line : lines) {
            infoConsumer.accept(format(Level.INFO, "startup", line));
        }
        infoConsumer.accept(color(ANSI_GRAY, SEPARATOR));
    }

    public void info(String message) {
        infoConsumer.accept(format(Level.INFO, "core", message));
    }

    public void success(String message) {
        infoConsumer.accept(format(Level.SUCCESS, "core", message));
    }

    public void warn(String message) {
        warnConsumer.accept(format(Level.WARN, "core", message));
    }

    public void error(String message) {
        errorConsumer.accept(format(Level.ERROR, "core", message), null);
    }

    public void error(String message, Throwable throwable) {
        errorConsumer.accept(format(Level.ERROR, "core", message), throwable);
    }

    public boolean isEnabled(ProudAuthSettings.Debugger debugger, DebugChannel channel) {
        return debugger != null && debugger.isEnabled(channel);
    }

    public void debug(ProudAuthSettings.Debugger debugger, DebugChannel channel, String template, Object... args) {
        if (!isEnabled(debugger, channel)) {
            return;
        }
        String message = template.formatted(args);
        infoConsumer.accept(format(Level.DEBUG, channel.configKey(), message, channelColor(channel)));
    }

    public void debugEvent(
            ProudAuthSettings.Debugger debugger,
            DebugChannel channel,
            String eventName,
            Object... keyValues
    ) {
        if (!isEnabled(debugger, channel)) {
            return;
        }
        for (String line : buildEventLines(eventName, keyValues)) {
            infoConsumer.accept(format(Level.DEBUG, channel.configKey(), line, channelColor(channel)));
        }
    }

    private String format(Level level, String scope, String message) {
        return format(level, scope, message, level.colorCode);
    }

    private String format(Level level, String scope, String message, String scopeColor) {
        String levelToken = color(level.colorCode, level.label);
        String scopeToken = color(scopeColor, normalize(scope));
        String body = "[" + levelToken + "] [" + scopeToken + "] " + message;

        if (!includePrefix) {
            return body;
        }

        String prefixToken = color(ANSI_BOLD + ANSI_CYAN, prefix);
        return prefixToken + " " + body;
    }

    private String color(String code, String text) {
        if (!ansiEnabled || code == null || code.isBlank()) {
            return text;
        }
        return code + text + ANSI_RESET;
    }

    private String channelColor(DebugChannel channel) {
        return switch (channel) {
            case PLAYER_RESOLUTION -> ANSI_LIGHT_BLUE;
            case SESSION_FLOW -> ANSI_LIGHT_GREEN;
            case PREMIUM_FLOW -> ANSI_YELLOW;
            case BRIDGE_FLOW -> ANSI_LIGHT_MAGENTA;
            case SECURITY_FLOW -> ANSI_RED;
            case PROTECTION_FLOW -> ANSI_LIGHT_GRAY;
            case IP_BAN_FLOW -> ANSI_RED;
            case PROFILE_FLOW -> ANSI_CYAN;
            case MOVEMENT_AUDIT -> ANSI_GRAY;
            case TELEPORT_AUDIT -> ANSI_GRAY;
            case COMMAND_FLOW -> ANSI_LIGHT_GREEN;
        };
    }

    private List<String> buildEventLines(String eventName, Object... keyValues) {
        List<String> lines = new ArrayList<>();
        lines.add(color(ANSI_BOLD + ANSI_LIGHT_BLUE, normalize(eventName)));
        if (keyValues == null || keyValues.length == 0) {
            return lines;
        }

        int pairCount = keyValues.length / 2;
        for (int i = 0; i < pairCount; i++) {
            Object rawKey = keyValues[i * 2];
            Object rawValue = keyValues[i * 2 + 1];
            String key = rawKey == null ? "field" + i : String.valueOf(rawKey);
            String value = rawValue == null ? "null" : String.valueOf(rawValue);
            lines.add(formatEventField(key, value));
        }
        if (keyValues.length % 2 != 0) {
            lines.add(formatEventField("malformed", "true"));
        }
        return lines;
    }

    private String formatEventField(String key, String value) {
        String normalizedKey = sanitizeKey(key);
        String alignedKey = padRight(normalizedKey, EVENT_KEY_WIDTH);
        String keyToken = color(ANSI_LIGHT_GRAY, alignedKey);
        String separatorToken = color(ANSI_GRAY, " = ");
        String valueToken = color(selectValueColor(normalizedKey, value), quoteIfNeeded(value));
        return "  " + keyToken + separatorToken + valueToken;
    }

    private String selectValueColor(String key, String value) {
        String normalizedKey = sanitizeKey(key).toLowerCase();
        String normalizedValue = value == null ? "null" : value.trim().toLowerCase();
        if ("true".equals(normalizedValue) || "false".equals(normalizedValue)) {
            return "true".equals(normalizedValue) ? ANSI_LIGHT_GREEN : ANSI_RED;
        }
        if (normalizedKey.contains("error")
                || normalizedKey.contains("reason")
                || normalizedKey.contains("status")
                || normalizedKey.contains("action")) {
            return ANSI_YELLOW;
        }
        if (normalizedKey.contains("player")
                || normalizedKey.contains("resolved_name")
                || normalizedKey.contains("assertion_name")
                || normalizedKey.contains("server")
                || normalizedKey.contains("proxy_mode")
                || normalizedKey.contains("message_type")
                || normalizedKey.contains("event")) {
            return ANSI_LIGHT_BLUE;
        }
        if (normalizedKey.contains("uuid")
                || normalizedKey.contains("ip")
                || normalizedKey.contains("token")
                || normalizedKey.contains("key")
                || normalizedKey.contains("hash")) {
            return ANSI_WHITE;
        }
        if (normalizedKey.contains("account_type")
                || normalizedKey.contains("join_mode")
                || normalizedKey.contains("legacy")
                || normalizedKey.contains("premium")) {
            return ANSI_LIGHT_MAGENTA;
        }
        return ANSI_WHITE;
    }

    private String padRight(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
    }

    private String quoteIfNeeded(String value) {
        if (value.indexOf(' ') >= 0 || value.indexOf('=') >= 0) {
            return '"' + value.replace("\"", "'") + '"';
        }
        return value;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }

    public static String colorLoggerName(String loggerName) {
        if ("true".equalsIgnoreCase(System.getProperty("proudauth.noAnsi", "false"))) {
            return loggerName;
        }
        return ANSI_BOLD + ANSI_CYAN + loggerName + ANSI_RESET;
    }

    private enum Level {
        INFO("INFO", ANSI_CYAN),
        SUCCESS("OK", ANSI_LIGHT_GREEN),
        WARN("WARN", ANSI_YELLOW),
        ERROR("ERR", ANSI_RED),
        DEBUG("DBG", ANSI_LIGHT_GRAY);

        private final String label;
        private final String colorCode;

        Level(String label, String colorCode) {
            this.label = label;
            this.colorCode = colorCode;
        }
    }
}
