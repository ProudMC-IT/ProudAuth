package com.monkey.proudAuth.velocity.config;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

public record VelocityPluginSettings(
        Database database,
        Premium premium,
        Bridge bridge,
        Guards guards,
        Reports reports,
        String language,
        ProudAuthSettings.Debugger debugger
) {

    public ProudAuthSettings toCommonSettings() {
        return new ProudAuthSettings(
                new ProudAuthSettings.Database(
                        database.host(),
                        database.port(),
                        database.name(),
                        database.user(),
                        database.password(),
                        database.poolSize()
                ),
                new ProudAuthSettings.Premium(
                        premium.enabled(),
                        false,
                        premium.apiTimeoutMs(),
                        premium.requireUuidProof()
                ),
                new ProudAuthSettings.Sessions(false, 0, false),
                new ProudAuthSettings.Security(
                        1,
                        300,
                        false,
                        180,
                        3,
                        false,
                        2,
                        3,
                        true
                ),
                new ProudAuthSettings.Protection(
                        false,
                        false,
                        false,
                        false,
                        false,
                        60,
                        new ProudAuthSettings.AuthSpawn(false, "world", 0.5D, 64D, 0.5D, 0F, 0F)
                ),
                new ProudAuthSettings.PasswordPolicy(1, 72, ""),
                new ProudAuthSettings.Proxy(ProudAuthSettings.ProxyMode.VELOCITY),
                new ProudAuthSettings.Bridge(
                        bridge.enabled(),
                        bridge.mode(),
                        bridge.transport(),
                        bridge.sharedSecret(),
                        bridge.assertionTtlSeconds()
                ),
                debugger
        );
    }

    public record Database(
            String host,
            int port,
            String name,
            String user,
            String password,
            int poolSize
    ) {
    }

    public record Premium(
            boolean enabled,
            int apiTimeoutMs,
            boolean rewriteGameProfile,
            boolean requireUuidProof,
            boolean autoPromoteVerifiedLowRisk,
            int autoPromoteMaxUsernamesPerIp1h,
            int autoPromoteMaxIpsPerUsername24h,
            boolean autoPromoteDenyWhenIpBanned
    ) {
    }

    public record Bridge(
            boolean enabled,
            ProudAuthSettings.BridgeMode mode,
            ProudAuthSettings.BridgeTransport transport,
            String sharedSecret,
            long assertionTtlSeconds,
            boolean backendCheckEnabled,
            int backendCheckTimeoutMs,
            int backendCheckPollIntervalMs
    ) {
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
