package com.monkey.proudAuth.velocity.config;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public final class VelocityConfigLoader {

    private final Object plugin;
    private final Path dataDirectory;
    private final Yaml yaml;
    private volatile VelocityPluginSettings settings;

    public VelocityConfigLoader(Object plugin, Path dataDirectory) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        reload();
    }

    public void reload() {
        ensureDefaults();
        Path configPath = dataDirectory.resolve("velocity-config.yml");
        Map<String, Object> root = loadMap(configPath);

        this.settings = new VelocityPluginSettings(
                new VelocityPluginSettings.Database(
                        string(root, "database.host", "localhost"),
                        integer(root, "database.port", 3306),
                        string(root, "database.name", "proudauth"),
                        string(root, "database.user", "root"),
                        string(root, "database.password", ""),
                        Math.max(1, integer(root, "database.pool-size", 10))
                ),
                new VelocityPluginSettings.Premium(
                        bool(root, "premium.enabled", true),
                        Math.max(500, integer(root, "premium.api-timeout-ms", 3000)),
                        bool(root, "premium.rewrite-game-profile", false)
                ),
                new VelocityPluginSettings.Bridge(
                        bool(root, "bridge.enabled", false),
                        ProudAuthSettings.BridgeMode.from(string(root, "bridge.mode", "FALLBACK")),
                        ProudAuthSettings.BridgeTransport.from(string(root, "bridge.transport", "MYSQL")),
                        string(root, "bridge.shared-secret", "change-me"),
                        Math.max(1, integer(root, "bridge.assertion-ttl-seconds", 10))
                ),
                new ProudAuthSettings.Debugger(
                        bool(root, "debugger.enabled", false),
                        bool(root, "debugger.player-resolution", true),
                        bool(root, "debugger.session-flow", true),
                        bool(root, "debugger.premium-flow", true),
                        bool(root, "debugger.bridge-flow", true),
                        bool(root, "debugger.security-flow", false),
                        bool(root, "debugger.protection-flow", true),
                        bool(root, "debugger.ip-ban-flow", true),
                        bool(root, "debugger.profile-flow", true),
                        bool(root, "debugger.movement-audit", false),
                        bool(root, "debugger.teleport-audit", false),
                        bool(root, "debugger.command-flow", true)
                )
        );
    }

    public VelocityPluginSettings settings() {
        return settings;
    }

    private void ensureDefaults() {
        try {
            Files.createDirectories(dataDirectory);
            Files.createDirectories(dataDirectory.resolve("lang"));
            copyIfMissing("velocity-config.yml", dataDirectory.resolve("velocity-config.yml"));
            copyIfMissing("lang/it.yml", dataDirectory.resolve("lang").resolve("it.yml"));
        } catch (IOException exception) {
            throw new IllegalStateException("Impossibile preparare i file di configurazione Velocity.", exception);
        }
    }

    private void copyIfMissing(String resourcePath, Path targetPath) throws IOException {
        if (Files.exists(targetPath)) {
            return;
        }

        try (InputStream inputStream = plugin.getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Risorsa mancante nel jar: " + resourcePath);
            }
            Files.copy(inputStream, targetPath);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadMap(Path path) {
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = yaml.load(reader);
            if (loaded instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            return Collections.emptyMap();
        } catch (IOException exception) {
            throw new IllegalStateException("Impossibile leggere " + path, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Object get(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String string(Map<String, Object> root, String path, String fallback) {
        Object value = get(root, path);
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> root, String path, boolean fallback) {
        Object value = get(root, path);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private int integer(Map<String, Object> root, String path, int fallback) {
        Object value = get(root, path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

}
