package com.monkey.proudAuth.common.config;

public record ProudAuthNetworkConfig(
        String language,
        ProudAuthSettings.Database database,
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
            boolean postAuthEnabled,
            String postAuthServer
    ) {
        public boolean hasAuthEntryServer() {
            return authEntryEnabled && authEntryServer != null && !authEntryServer.isBlank();
        }

        public boolean hasPostAuthServer() {
            return postAuthEnabled && postAuthServer != null && !postAuthServer.isBlank();
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
