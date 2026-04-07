package com.monkey.proudAuth.velocity.config;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

public record VelocityPluginSettings(
        boolean debugEnabled,
        Database database,
        Premium premium,
        Bridge bridge
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
                        premium.apiTimeoutMs()
                ),
                new ProudAuthSettings.Sessions(false, 0, false),
                new ProudAuthSettings.Security(1, 300, false),
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
                )
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
            int apiTimeoutMs
    ) {
    }

    public record Bridge(
            boolean enabled,
            ProudAuthSettings.BridgeMode mode,
            ProudAuthSettings.BridgeTransport transport,
            String sharedSecret,
            long assertionTtlSeconds
    ) {
    }
}
