package com.monkey.proudAuth.velocity.config;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
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
import java.util.Objects;

public final class VelocityLang {

    private final Object plugin;
    private final Path dataDirectory;
    private final MiniMessage miniMessage;
    private final Yaml yaml;
    private volatile Map<String, Object> configuration;

    public VelocityLang(Object plugin, Path dataDirectory) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.miniMessage = MiniMessage.miniMessage();
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        reload();
    }

    public void reload() {
        try {
            Files.createDirectories(dataDirectory.resolve("lang"));
            Path langPath = dataDirectory.resolve("lang").resolve("it.yml");
            if (!Files.exists(langPath)) {
                try (InputStream inputStream = plugin.getClass().getClassLoader().getResourceAsStream("lang/it.yml")) {
                    if (inputStream == null) {
                        throw new IOException("Risorsa lang/it.yml mancante nel jar.");
                    }
                    Files.copy(inputStream, langPath);
                }
            }

            try (Reader reader = Files.newBufferedReader(langPath)) {
                Object loaded = yaml.load(reader);
                if (loaded instanceof Map<?, ?> map) {
                    //noinspection unchecked
                    this.configuration = (Map<String, Object>) map;
                } else {
                    this.configuration = Collections.emptyMap();
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Impossibile caricare il language file Velocity.", exception);
        }
    }

    public Component message(String key, TagResolver... resolvers) {
        String body = string(key, key);
        String prefix = string("prefix", "");
        String payload = Objects.equals(key, "prefix") ? body : prefix + body;
        return miniMessage.deserialize(payload, resolvers);
    }

    public void send(Audience audience, String key, TagResolver... resolvers) {
        audience.sendMessage(message(key, resolvers));
    }

    private String string(String key, String fallback) {
        Object value = configuration.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
