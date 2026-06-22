package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.util.LocationSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Optional;

public final class PluginConfig {

    private final JavaPlugin plugin;
    private volatile Bootstrap bootstrap;
    private volatile ProudAuthSettings settings;
    private volatile String language;
    private volatile Map<String, String> centralizedLanguageMessages;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        this.bootstrap = new Bootstrap(
                new ProudAuthSettings.Database(
                        config.getString("database.host", "localhost"),
                        config.getInt("database.port", 3306),
                        config.getString("database.name", "proudauth"),
                        config.getString("database.user", "root"),
                        config.getString("database.password", ""),
                        Math.max(1, config.getInt("database.pool-size", 10))
                ),
                config.getString("network.server-id", ""),
                config.getString("network.region-id", ""),
                Math.max(1, config.getInt("config-sync.poll-interval-seconds", 2)),
                config.getBoolean("updates.check-enabled", true),
                config.getBoolean("updates.notify-admin-on-join", true)
        );
        this.settings = bootstrap.storageBootstrapSettings();
        this.language = "it";
        this.centralizedLanguageMessages = Map.of();
    }

    public void applyNetworkConfig(ProudAuthNetworkConfig networkConfig) {
        this.settings = networkConfig.toBackendSettings(bootstrap.database());
        this.language = networkConfig.language();
        this.centralizedLanguageMessages = networkConfig.languageBundle().messages();
    }

    public ProudAuthSettings settings() {
        return settings;
    }

    public ProudAuthSettings storageBootstrapSettings() {
        return bootstrap.storageBootstrapSettings();
    }

    public String language() {
        return language;
    }

    public Map<String, String> centralizedLanguageMessages() {
        return centralizedLanguageMessages;
    }

    public Optional<Location> authSpawn(Server server) {
        if (settings.protection().authSpawn().useWorldSpawn()) {
            World primaryWorld = server.getWorlds().isEmpty() ? null : server.getWorlds().get(0);
            if (primaryWorld == null) {
                return Optional.empty();
            }
            return Optional.of(primaryWorld.getSpawnLocation());
        }
        if (!settings.protection().authSpawn().enabled()) {
            return Optional.empty();
        }
        return LocationSerializer.deserialize(server, settings.protection().authSpawn());
    }

    public String serverId() {
        String configured = bootstrap.serverId();
        if (configured == null || configured.isBlank()) {
            return plugin.getServer().getName();
        }
        return configured.trim();
    }

    public int configSyncPollIntervalSeconds() {
        return bootstrap.configSyncPollIntervalSeconds();
    }

    public String regionId() {
        String configured = bootstrap.regionId();
        return configured == null ? "" : configured.trim();
    }

    public boolean updateCheckEnabled() {
        return bootstrap.updateCheckEnabled();
    }

    public boolean notifyAdminOnJoinEnabled() {
        return bootstrap.notifyAdminOnJoinEnabled();
    }

    private record Bootstrap(
            ProudAuthSettings.Database database,
            String serverId,
            String regionId,
            int configSyncPollIntervalSeconds,
            boolean updateCheckEnabled,
            boolean notifyAdminOnJoinEnabled
    ) {
        private ProudAuthSettings storageBootstrapSettings() {
            return new ProudAuthSettings(
                    database,
                    new ProudAuthSettings.Premium(
                            true,
                            true,
                            3000,
                            true,
                            ProudAuthSettings.PremiumMode.STRICT_GLOBAL,
                            300L,
                            ProudAuthSettings.LegacyUnsupportedAction.FORCE_ONLINE
                    ),
                    new ProudAuthSettings.Sessions(true, 1440, true),
                    new ProudAuthSettings.Security(5, 300L, false, 180, 3, false, 2, 3, true),
                    new ProudAuthSettings.Protection(
                            true,
                            true,
                            true,
                            true,
                            true,
                            60L,
                            new ProudAuthSettings.AuthSpawn(false, false, "world", 0.5D, 64D, 0.5D, 0F, 0F),
                            true,
                            java.util.List.of("/login", "/register", "/2fa", "/sp", "/cracked", "/premium", "/proudauthbackend"),
                            java.util.List.of("/sp", "/cracked", "/premium", "/proudauthbackend"),
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            true,
                            ProudAuthSettings.VisualEffect.NONE
                    ),
                    new ProudAuthSettings.PasswordPolicy(6, 32, ""),
                    new ProudAuthSettings.Proxy(ProudAuthSettings.ProxyMode.VELOCITY),
                    new ProudAuthSettings.Bridge(
                            true,
                            ProudAuthSettings.BridgeMode.FALLBACK,
                            ProudAuthSettings.BridgeTransport.MYSQL,
                            "change-me",
                            10L
                    ),
                    new ProudAuthSettings.Debugger(
                            false,
                            true,
                            true,
                            true,
                            true,
                            false,
                            true,
                            true,
                            true,
                            false,
                            false,
                            true
                    )
            );
        }
    }
}
