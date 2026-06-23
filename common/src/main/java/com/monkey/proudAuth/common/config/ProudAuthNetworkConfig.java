package com.monkey.proudAuth.common.config;

import java.util.Locale;
import java.util.Map;

public record ProudAuthNetworkConfig(
        String language,
        LanguageBundle languageBundle,
        ProudAuthSettings.Database database,
        Monitor monitor,
        ProudAuthSettings.Premium premium,
        ProudAuthSettings.Sessions sessions,
        ProudAuthSettings.Security security,
        ProudAuthSettings.Protection protection,
        ProudAuthSettings.PasswordPolicy passwordPolicy,
        ProudAuthSettings.Bridge bridge,
        ProudAuthSettings.Debugger debugger,
        PaAccess paAccess,
        Proxy proxy
) {

    public ProudAuthSettings toBackendSettings(ProudAuthSettings.Database databaseOverride) {
        ProudAuthSettings.Database effectiveDatabase = databaseOverride == null ? database : databaseOverride;
        return new ProudAuthSettings(
                effectiveDatabase,
                premium,
                sessions,
                security,
                protection,
                passwordPolicy,
                new ProudAuthSettings.Proxy(ProudAuthSettings.ProxyMode.VELOCITY),
                bridge,
                debugger
        );
    }

    public record LanguageBundle(
            java.util.Map<String, String> messages
    ) {
        public LanguageBundle {
            messages = messages == null ? java.util.Map.of() : java.util.Map.copyOf(messages);
        }

        public boolean hasMessages() {
            return !messages.isEmpty();
        }
    }

    public record Monitor(
            String networkName
    ) {
    }

    public record PaAccess(
            boolean enabled,
            OwnerConflictPolicy ownerConflictPolicy,
            History history,
            PremiumTargets premiumTargets
    ) {
    }

    public enum OwnerConflictPolicy {
        OWNER_TAKES_OVER,
        KICK_DELEGATE,
        DENY_OWNER;

        public static OwnerConflictPolicy from(String raw) {
            if (raw == null || raw.isBlank()) {
                return OWNER_TAKES_OVER;
            }
            try {
                return OwnerConflictPolicy.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return OWNER_TAKES_OVER;
            }
        }
    }

    public record History(
            String timezone,
            String timeFormat,
            int pageSize,
            int retentionDays
    ) {
    }

    public record PremiumTargets(
            boolean enabled,
            boolean warnOnStartup
    ) {
    }

    public record Proxy(
            String onlineModeDeniedMessage,
            String claimFailedDeniedMessage,
            BridgeBackendCheck bridgeBackendCheck,
            Routing routing,
            Regions regions,
            Guards guards,
            Reports reports
    ) {
        public Routing routingForRegion(String regionId) {
            if (regions != null && regions.enabled()) {
                Routing regionalRouting = regions.routing(regionId).orElse(null);
                if (regionalRouting != null) {
                    return regionalRouting;
                }
            }
            return routing;
        }

        public Routing routingForServer(String serverName, String fallbackRegionId) {
            String resolvedRegionId = regionIdForServer(serverName).orElse(fallbackRegionId);
            return routingForRegion(resolvedRegionId);
        }

        public java.util.Optional<String> regionIdForServer(String serverName) {
            if (regions == null || !regions.enabled() || serverName == null || serverName.isBlank()) {
                return java.util.Optional.empty();
            }

            String normalizedServerName = normalizeToken(serverName);
            for (Map.Entry<String, Routing> entry : regions.entries().entrySet()) {
                if (matchesRoutingServer(entry.getValue(), normalizedServerName)) {
                    return java.util.Optional.of(entry.getKey());
                }
            }

            for (String regionId : regions.entries().keySet()) {
                if (matchesRegionNaming(normalizedServerName, regionId)) {
                    return java.util.Optional.of(regionId);
                }
            }

            return java.util.Optional.empty();
        }

        private boolean matchesRoutingServer(Routing routing, String normalizedServerName) {
            return routing != null
                    && ((routing.hasAuthEntryServer()
                    && normalizeToken(routing.authEntryServer()).equals(normalizedServerName))
                    || (routing.hasPostAuthServer()
                    && normalizeToken(routing.postAuthServer()).equals(normalizedServerName)));
        }

        private boolean matchesRegionNaming(String normalizedServerName, String regionId) {
            String normalizedRegionId = normalizeToken(regionId);
            return normalizedServerName.equals(normalizedRegionId)
                    || normalizedServerName.endsWith("-" + normalizedRegionId)
                    || normalizedServerName.endsWith("_" + normalizedRegionId)
                    || normalizedServerName.startsWith(normalizedRegionId + "-")
                    || normalizedServerName.startsWith(normalizedRegionId + "_");
        }

        private String normalizeToken(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record BridgeBackendCheck(
            boolean enabled,
            int timeoutMs,
            int pollIntervalMs
    ) {
    }

    public record Routing(
            boolean authEntryEnabled,
            String authEntryServer,
            boolean authEntryProtected,
            boolean postAuthEnabled,
            String postAuthServer,
            AuthenticatedJoinRoutingMode authenticatedJoinRoutingMode,
            int postAuthRedirectDelayMs,
            int legacyPostAuthRedirectDelayMs
    ) {
        public boolean hasAuthEntryServer() {
            return authEntryEnabled && authEntryServer != null && !authEntryServer.isBlank();
        }

        public boolean hasPostAuthServer() {
            return postAuthEnabled && postAuthServer != null && !postAuthServer.isBlank();
        }

        public boolean shouldDirectAuthenticatedJoinsToPostAuth() {
            return authenticatedJoinRoutingMode == AuthenticatedJoinRoutingMode.DIRECT_TO_POST_AUTH
                    && hasPostAuthServer();
        }
    }

    public record Regions(
            boolean enabled,
            Map<String, Routing> entries
    ) {
        public Regions {
            entries = entries == null ? Map.of() : Map.copyOf(entries);
        }

        public java.util.Optional<Routing> routing(String regionId) {
            if (!enabled || regionId == null || regionId.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.ofNullable(entries.get(regionId.trim().toLowerCase(Locale.ROOT)));
        }
    }

    public enum AuthenticatedJoinRoutingMode {
        ALWAYS_PASS_AUTH,
        DIRECT_TO_POST_AUTH;

        public static AuthenticatedJoinRoutingMode from(String raw) {
            if (raw == null || raw.isBlank()) {
                return ALWAYS_PASS_AUTH;
            }
            try {
                return AuthenticatedJoinRoutingMode.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return ALWAYS_PASS_AUTH;
            }
        }
    }

    public record Guards(
            boolean enabled,
            int antiBotWindowSeconds,
            int antiBotMaxConnectionsPerIp,
            long antiBotBanSeconds,
            int identityWindowSeconds,
            int identityMaxUsernamesPerIp,
            int identityMaxIpsPerUsername,
            long identityBanSeconds,
            int historyRetentionDays
    ) {
    }

    public record Reports(
            boolean autoExportEnabled,
            int autoExportIntervalMinutes,
            int autoExportWindowHours,
            int autoExportLimit
    ) {
    }
}
