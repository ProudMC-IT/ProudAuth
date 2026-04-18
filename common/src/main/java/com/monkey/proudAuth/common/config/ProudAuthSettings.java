package com.monkey.proudAuth.common.config;

import com.monkey.proudAuth.common.logging.DebugChannel;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public record ProudAuthSettings(
        Database database,
        Premium premium,
        Sessions sessions,
        Security security,
        Protection protection,
        PasswordPolicy passwordPolicy,
        Proxy proxy,
        Bridge bridge,
        Debugger debugger
) {

    public record Database(String host, int port, String name, String user, String password, int poolSize) {
    }

    public record Premium(boolean enabled, boolean autoLogin, int apiTimeoutMs, boolean requireUuidProof) {
    }

    public record Sessions(boolean enabled, long ttlMinutes, boolean bindToIp) {
    }

    public record Security(
            int maxAttempts,
            long lockoutSeconds,
            boolean totpEnabled,
            int totpSetupTimeoutSeconds,
            int totpSetupMaxAttempts,
            boolean totpDefaultFlowAlways,
            int totpSuspiciousMaxUsernamesPerIp1h,
            int totpSuspiciousMaxIpsPerUsername24h,
            boolean totpSuspiciousDenyWhenIpBanned
    ) {
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

    public record Debugger(
            boolean enabled,
            boolean playerResolution,
            boolean sessionFlow,
            boolean premiumFlow,
            boolean bridgeFlow,
            boolean securityFlow,
            boolean protectionFlow,
            boolean ipBanFlow,
            boolean profileFlow,
            boolean movementAudit,
            boolean teleportAudit,
            boolean commandFlow
    ) {
        public boolean isEnabled(DebugChannel channel) {
            if (!enabled) {
                return false;
            }
            return switch (channel) {
                case PLAYER_RESOLUTION -> playerResolution;
                case SESSION_FLOW -> sessionFlow;
                case PREMIUM_FLOW -> premiumFlow;
                case BRIDGE_FLOW -> bridgeFlow;
                case SECURITY_FLOW -> securityFlow;
                case PROTECTION_FLOW -> protectionFlow;
                case IP_BAN_FLOW -> ipBanFlow;
                case PROFILE_FLOW -> profileFlow;
                case MOVEMENT_AUDIT -> movementAudit;
                case TELEPORT_AUDIT -> teleportAudit;
                case COMMAND_FLOW -> commandFlow;
            };
        }

        public String summary() {
            if (!enabled) {
                return "disabled";
            }

            String activeChannels = Arrays.stream(DebugChannel.values())
                    .filter(this::isEnabled)
                    .map(DebugChannel::configKey)
                    .collect(Collectors.joining(", "));
            return activeChannels.isBlank() ? "enabled (no channels)" : activeChannels;
        }
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
