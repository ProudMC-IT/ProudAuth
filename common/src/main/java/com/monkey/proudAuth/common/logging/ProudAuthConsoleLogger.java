package com.monkey.proudAuth.common.logging;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

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
    private static final String SEPARATOR = "------------------------------------------------------------";
    private static final int CHANNEL_WIDTH = 16;

    private final String prefix;
    private final Consumer<String> infoConsumer;
    private final Consumer<String> warnConsumer;
    private final BiConsumer<String, Throwable> errorConsumer;
    private final boolean ansiEnabled;

    public ProudAuthConsoleLogger(
            String name,
            Consumer<String> infoConsumer,
            Consumer<String> warnConsumer,
            BiConsumer<String, Throwable> errorConsumer
    ) {
        String baseName = Objects.requireNonNull(name, "name");
        this.prefix = "[" + baseName + "]";
        this.infoConsumer = Objects.requireNonNull(infoConsumer, "infoConsumer");
        this.warnConsumer = Objects.requireNonNull(warnConsumer, "warnConsumer");
        this.errorConsumer = Objects.requireNonNull(errorConsumer, "errorConsumer");
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
        String message = buildEventMessage(eventName, keyValues);
        infoConsumer.accept(format(Level.DEBUG, channel.configKey(), message, channelColor(channel)));
    }

    private String format(Level level, String scope, String message) {
        return format(level, scope, message, level.colorCode);
    }

    private String format(Level level, String scope, String message, String scopeColor) {
        String paddedScope = pad(scope, CHANNEL_WIDTH);
        String levelToken = color(level.colorCode, pad(level.label, 5));
        String scopeToken = color(scopeColor, paddedScope);
        String prefixToken = color(ANSI_BOLD + ANSI_WHITE, prefix);
        return prefixToken + " " + "[" + levelToken + "]" + " [" + scopeToken + "] " + message;
    }

    private String pad(String value, int width) {
        if (value == null) {
            return " ".repeat(width);
        }
        if (value.length() >= width) {
            return value.substring(0, width);
        }
        return value + " ".repeat(width - value.length());
    }

    private String color(String code, String text) {
        if (!ansiEnabled || code == null || code.isBlank()) {
            return text;
        }
        return code + text + ANSI_RESET;
    }

    private String channelColor(DebugChannel channel) {
        return switch (channel) {
            case PLAYER_RESOLUTION -> ANSI_CYAN;
            case SESSION_FLOW -> ANSI_GREEN;
            case PREMIUM_FLOW -> ANSI_YELLOW;
            case BRIDGE_FLOW -> ANSI_MAGENTA;
            case SECURITY_FLOW -> ANSI_RED;
            case PROTECTION_FLOW -> ANSI_WHITE;
            case IP_BAN_FLOW -> ANSI_RED;
            case PROFILE_FLOW -> ANSI_CYAN;
            case MOVEMENT_AUDIT -> ANSI_GRAY;
            case TELEPORT_AUDIT -> ANSI_GRAY;
            case COMMAND_FLOW -> ANSI_GREEN;
        };
    }

    private String buildEventMessage(String eventName, Object... keyValues) {
        StringBuilder builder = new StringBuilder();
        builder.append("event=").append(normalize(eventName));
        if (keyValues == null || keyValues.length == 0) {
            return builder.toString();
        }
        int pairCount = keyValues.length / 2;
        for (int i = 0; i < pairCount; i++) {
            Object rawKey = keyValues[i * 2];
            Object rawValue = keyValues[i * 2 + 1];
            String key = rawKey == null ? "field" + i : String.valueOf(rawKey);
            String value = rawValue == null ? "null" : String.valueOf(rawValue);
            builder.append(' ')
                    .append(sanitizeKey(key))
                    .append('=')
                    .append(quoteIfNeeded(value));
        }
        if (keyValues.length % 2 != 0) {
            builder.append(" malformed=true");
        }
        return builder.toString();
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

    private enum Level {
        INFO("INFO", ANSI_CYAN),
        SUCCESS("OK", ANSI_GREEN),
        WARN("WARN", ANSI_YELLOW),
        ERROR("ERR", ANSI_RED),
        DEBUG("DBG", ANSI_GRAY);

        private final String label;
        private final String colorCode;

        Level(String label, String colorCode) {
            this.label = label;
            this.colorCode = colorCode;
        }
    }
}
