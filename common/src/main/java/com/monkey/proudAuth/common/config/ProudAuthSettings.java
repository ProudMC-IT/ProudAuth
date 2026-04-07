package com.monkey.proudAuth.common.config;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record ProudAuthSettings(
        Database database,
        Premium premium,
        Sessions sessions,
        Security security,
        Protection protection,
        PasswordPolicy passwordPolicy,
        Proxy proxy,
        Bridge bridge
) {

    public record Database(String host, int port, String name, String user, String password, int poolSize) {
    }

    public record Premium(boolean enabled, boolean autoLogin, int apiTimeoutMs) {
    }

    public record Sessions(boolean enabled, long ttlMinutes, boolean bindToIp) {
    }

    public record Security(int maxAttempts, long lockoutSeconds, boolean totpEnabled) {
    }

    public record Protection(
            boolean blockMovement,
            boolean blockChat,
            boolean blockInteractions,
            boolean invisibleUntilAuth,
            boolean noDropOnDeath,
            long authTimeoutSeconds,
            AuthSpawn authSpawn
    ) {
    }

    public record AuthSpawn(boolean enabled, String world, double x, double y, double z, float yaw, float pitch) {
    }

    public record PasswordPolicy(int minLength, int maxLength, String complexityRegex) {
        public Optional<Pattern> compiledPattern() {
            if (complexityRegex == null || complexityRegex.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(Pattern.compile(complexityRegex));
        }
    }

    public record Proxy(ProxyMode mode) {
    }

    public record Bridge(
            boolean enabled,
            BridgeMode mode,
            BridgeTransport transport,
            String sharedSecret,
            long assertionTtlSeconds
    ) {
    }

    public enum ProxyMode {
        NONE,
        BUNGEE,
        VELOCITY;

        public static ProxyMode from(String raw) {
            try {
                return ProxyMode.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return NONE;
            }
        }
    }

    public enum BridgeMode {
        FALLBACK,
        STRICT;

        public static BridgeMode from(String raw) {
            try {
                return BridgeMode.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return FALLBACK;
            }
        }
    }

    public enum BridgeTransport {
        MYSQL;

        public static BridgeTransport from(String raw) {
            try {
                return BridgeTransport.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return MYSQL;
            }
        }
    }
}
