package com.monkey.proudAuth.velocity.config;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfigCodec;
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
    private final Path configPath;
    private final Yaml yaml;
    private final ProudAuthNetworkConfigCodec codec;
    private volatile ProudAuthNetworkConfig settings;
    private volatile long lastModifiedMillis;
    private volatile int fileWatchIntervalSeconds;
    private volatile String proxyRegionId;
    private volatile String proxyNodeId;

    public VelocityConfigLoader(Object plugin, Path dataDirectory) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.configPath = dataDirectory.resolve("velocity-config.yml");
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        this.codec = new ProudAuthNetworkConfigCodec();
        reload();
    }

    public void reload() {
        ensureDefaults();
        Map<String, Object> root = loadMap(configPath);
        try {
            this.settings = codec.parse(Files.readString(configPath));
            this.lastModifiedMillis = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException exception) {
            throw new IllegalStateException("Impossibile leggere " + configPath, exception);
        }
        this.fileWatchIntervalSeconds = Math.max(1, integer(root, "config-sync.file-watch-interval-seconds", 2));
        this.proxyRegionId = string(root, "proxy-local.region-id", "");
        this.proxyNodeId = string(root, "proxy-local.node-id", "");
    }

    public ProudAuthNetworkConfig settings() {
        return settings;
    }

    public int fileWatchIntervalSeconds() {
        return fileWatchIntervalSeconds;
    }

    public boolean hasFileChanged() {
        try {
            return Files.exists(configPath) && Files.getLastModifiedTime(configPath).toMillis() > lastModifiedMillis;
        } catch (IOException exception) {
            return false;
        }
    }

    public Path configPath() {
        return configPath;
    }

    public String proxyRegionId() {
        return proxyRegionId == null ? "" : proxyRegionId.trim();
    }

    public String proxyNodeId() {
        return proxyNodeId == null ? "" : proxyNodeId.trim();
    }

    private void ensureDefaults() {
        try {
            Files.createDirectories(dataDirectory);
            copyIfMissing("velocity-config.yml", configPath);
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

    private int integer(Map<String, Object> root, String path, int fallback) {
        Object value = get(root, path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private String string(Map<String, Object> root, String path, String fallback) {
        Object value = get(root, path);
        return value == null ? fallback : String.valueOf(value);
    }
}
