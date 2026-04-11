package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.util.LocationSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

public final class PluginConfig {

    private final JavaPlugin plugin;
    private volatile ProudAuthSettings settings;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        this.settings = new ProudAuthSettings(
                new ProudAuthSettings.Database(
                        config.getString("database.host", "localhost"),
                        config.getInt("database.port", 3306),
                        config.getString("database.name", "proudauth"),
                        config.getString("database.user", "root"),
                        config.getString("database.password", ""),
                        Math.max(1, config.getInt("database.pool-size", 10))
                ),
                new ProudAuthSettings.Premium(
                        config.getBoolean("premium.enabled", true),
                        config.getBoolean("premium.auto-login", true),
                        Math.max(500, config.getInt("premium.api-timeout-ms", 3000))
                ),
                new ProudAuthSettings.Sessions(
                        config.getBoolean("sessions.enabled", true),
                        Math.max(0, config.getLong("sessions.ttl-minutes", 1440)),
                        config.getBoolean("sessions.bind-to-ip", true)
                ),
                new ProudAuthSettings.Security(
                        Math.max(1, config.getInt("security.max-attempts", 5)),
                        Math.max(1, config.getLong("security.lockout-seconds", 300)),
                        config.getBoolean("security.totp-enabled", false)
                ),
                new ProudAuthSettings.Protection(
                        config.getBoolean("protection.block-movement", true),
                        config.getBoolean("protection.block-chat", true),
                        config.getBoolean("protection.block-interactions", true),
                        config.getBoolean("protection.invisible-until-auth", true),
                        config.getBoolean("protection.no-drop-on-death", true),
                        Math.max(5, config.getLong("protection.auth-timeout-seconds", 60)),
                        new ProudAuthSettings.AuthSpawn(
                                config.getBoolean("protection.auth-spawn.enabled", false),
                                config.getString("protection.auth-spawn.world", "world"),
                                config.getDouble("protection.auth-spawn.x", 0.5D),
                                config.getDouble("protection.auth-spawn.y", 64D),
                                config.getDouble("protection.auth-spawn.z", 0.5D),
                                (float) config.getDouble("protection.auth-spawn.yaw", 0D),
                                (float) config.getDouble("protection.auth-spawn.pitch", 0D)
                        )
                ),
                new ProudAuthSettings.PasswordPolicy(
                        Math.max(1, config.getInt("password.min-length", 6)),
                        Math.max(1, config.getInt("password.max-length", 32)),
                        config.getString("password.complexity-regex", "")
                ),
                new ProudAuthSettings.Proxy(
                        ProudAuthSettings.ProxyMode.from(config.getString("proxy.mode", "NONE"))
                ),
                new ProudAuthSettings.Bridge(
                        config.getBoolean("bridge.enabled", false),
                        ProudAuthSettings.BridgeMode.from(config.getString("bridge.mode", "FALLBACK")),
                        ProudAuthSettings.BridgeTransport.from(config.getString("bridge.transport", "MYSQL")),
                        config.getString("bridge.shared-secret", "change-me"),
                        Math.max(1L, config.getLong("bridge.assertion-ttl-seconds", 10L))
                ),
                new ProudAuthSettings.Debugger(
                        config.getBoolean("debugger.enabled", false),
                        config.getBoolean("debugger.player-resolution", true),
                        config.getBoolean("debugger.session-flow", true),
                        config.getBoolean("debugger.premium-flow", true),
                        config.getBoolean("debugger.bridge-flow", true),
                        config.getBoolean("debugger.security-flow", false),
                        config.getBoolean("debugger.protection-flow", true),
                        config.getBoolean("debugger.ip-ban-flow", true),
                        config.getBoolean("debugger.profile-flow", true),
                        config.getBoolean("debugger.movement-audit", false),
                        config.getBoolean("debugger.teleport-audit", false),
                        config.getBoolean("debugger.command-flow", true)
                )
        );
    }

    public ProudAuthSettings settings() {
        return settings;
    }

    public String language() {
        return plugin.getConfig().getString("language", "it");
    }

    public Optional<Location> authSpawn(Server server) {
        if (!settings.protection().authSpawn().enabled()) {
            return Optional.empty();
        }
        return LocationSerializer.deserialize(server, settings.protection().authSpawn());
    }
}
