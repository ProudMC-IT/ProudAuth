package com.monkey.proudAuth.common.config;

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

    public record Proxy(
            String onlineModeDeniedMessage,
            String claimFailedDeniedMessage,
            BridgeBackendCheck bridgeBackendCheck,
            Routing routing,
            Guards guards,
            Reports reports
    ) {
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
