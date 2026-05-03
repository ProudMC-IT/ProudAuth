package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.util.LocationSerializer;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

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
                        Math.max(500, config.getInt("premium.api-timeout-ms", 3000)),
                        config.getBoolean("premium.require-uuid-proof", true),
                        ProudAuthSettings.PremiumMode.from(config.getString("premium.mode", "STRICT_GLOBAL")),
                        Math.max(30L, config.getLong("premium.claim-pending-seconds", 300L)),
                        ProudAuthSettings.LegacyUnsupportedAction.from(config.getString(
                                "premium.proxy-custom.legacy-unsupported-action",
                                "FORCE_ONLINE"
                        ))
                ),
                new ProudAuthSettings.Sessions(
                        config.getBoolean("sessions.enabled", true),
                        Math.max(0, config.getLong("sessions.ttl-minutes", 1440)),
                        config.getBoolean("sessions.bind-to-ip", true)
                ),
                new ProudAuthSettings.Security(
                        Math.max(1, config.getInt("security.max-attempts", 5)),
                        Math.max(1, config.getLong("security.lockout-seconds", 300)),
                        config.getBoolean("security.totp-enabled", false),
                        Math.max(30, config.getInt("security.totp-setup-timeout-seconds", 180)),
                        Math.max(1, config.getInt("security.totp-setup-max-attempts", 3)),
                        config.getBoolean("security.totp-default-flow-always", false),
                        Math.max(1, config.getInt("security.totp-suspicious.max-usernames-per-ip-1h", 2)),
                        Math.max(1, config.getInt("security.totp-suspicious.max-ips-per-username-24h", 3)),
                        config.getBoolean("security.totp-suspicious.deny-when-ip-banned", true)
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
                                config.getBoolean("protection.auth-spawn.use-world-spawn", false),
                                config.getString("protection.auth-spawn.world", "world"),
                                config.getDouble("protection.auth-spawn.x", 0.5D),
                                config.getDouble("protection.auth-spawn.y", 64D),
                                config.getDouble("protection.auth-spawn.z", 0.5D),
                                (float) config.getDouble("protection.auth-spawn.yaw", 0D),
                                (float) config.getDouble("protection.auth-spawn.pitch", 0D)
                        ),
                        config.getBoolean("protection.commands.block-commands", true),
                        normalizedCommands(config.getStringList("protection.commands.allowed-while-protected"),
                                List.of("/login", "/register", "/2fa", "/sp", "/cracked", "/premium", "/proudauthbackend")),
                        normalizedCommands(config.getStringList("protection.commands.allowed-during-claim-choice"),
                                List.of("/sp", "/cracked", "/premium", "/proudauthbackend")),
                        config.getBoolean("protection.restrictions.block-block-break",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-block-place",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-pvp-attack",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-pvp-take-damage",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-item-pickup",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-food-level-change",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-item-consume",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-swap-hand-items",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-book-edit",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-inventory",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-item-drop",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-entity-interact",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.restrictions.block-world-interact",
                                config.getBoolean("protection.block-interactions", true)),
                        config.getBoolean("protection.visibility.hide-protected-player-from-others",
                                config.getBoolean("protection.invisible-until-auth", true)),
                        config.getBoolean("protection.visibility.hide-other-players-from-protected-player",
                                config.getBoolean("protection.invisible-until-auth", true)),
                        ProudAuthSettings.VisualEffect.from(
                                config.getString("protection.effects.visual-effect", "NONE")
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
        String configured = plugin.getConfig().getString("network.server-id", "");
        if (configured == null || configured.isBlank()) {
            return plugin.getServer().getName();
        }
        return configured.trim();
    }

    private List<String> normalizedCommands(List<String> configuredValues, List<String> fallbackValues) {
        List<String> source = configuredValues == null || configuredValues.isEmpty() ? fallbackValues : configuredValues;
        return source.stream()
                .map(this::normalizeCommand)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
