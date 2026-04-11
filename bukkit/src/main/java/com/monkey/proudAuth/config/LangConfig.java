package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.lang.LanguageFileSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class LangConfig {

    private static final int LOGGED_KEY_LIMIT = 10;

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final MiniMessage miniMessage;
    private volatile Map<String, String> configuration;
    private volatile String activeLanguageDescription;

    public LangConfig(JavaPlugin plugin, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.miniMessage = MiniMessage.miniMessage();
        this.configuration = Collections.emptyMap();
        this.activeLanguageDescription = LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (bundled default)";
        reload();
    }

    public void reload() {
        try {
            Path langDirectory = plugin.getDataFolder().toPath().resolve("lang");
            Files.createDirectories(langDirectory);

            Map<String, String> defaultMessages = loadBundledMessages(LanguageFileSupport.DEFAULT_LANGUAGE_FILE);
            copyBundledLanguageIfMissing(langDirectory, LanguageFileSupport.DEFAULT_LANGUAGE_FILE);

            String requestedFileName = LanguageFileSupport.normalizeConfiguredLanguage(pluginConfig.language());
            LoadedLanguage loadedLanguage = resolveConfiguredLanguage(langDirectory, requestedFileName, defaultMessages);
            this.configuration = loadedLanguage.messages();
            this.activeLanguageDescription = loadedLanguage.description();
        } catch (Exception exception) {
            plugin.getLogger().severe("Impossibile caricare i file lingua: " + exception.getMessage());
            this.configuration = Collections.emptyMap();
            this.activeLanguageDescription = LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (unavailable)";
        }
    }

    public Component message(String key, TagResolver... resolvers) {
        String body = string(key, key);
        String prefix = string("prefix", "");
        String payload = Objects.equals(key, "prefix") ? body : prefix + body;
        return miniMessage.deserialize(payload, resolvers);
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(message(key, resolvers));
    }

    public String activeLanguageDescription() {
        return activeLanguageDescription;
    }

    private LoadedLanguage resolveConfiguredLanguage(Path langDirectory,
                                                     String requestedFileName,
                                                     Map<String, String> defaultMessages) throws IOException {
        Set<String> requiredKeys = defaultMessages.keySet();
        Optional<Path> externalPath = LanguageFileSupport.findLanguageFile(langDirectory, requestedFileName);
        if (externalPath.isPresent()) {
            Path selectedPath = externalPath.get();
            Map<String, String> externalMessages = loadFileMessages(selectedPath);
            if (validateConfiguration(externalMessages, requiredKeys, "external file " + selectedPath.toAbsolutePath())) {
                return new LoadedLanguage(externalMessages, selectedPath.getFileName() + " (external)");
            }

            Optional<Map<String, String>> bundledMessages = loadOptionalBundledMessages(requestedFileName);
            if (bundledMessages.isPresent() && validateConfiguration(
                    bundledMessages.get(),
                    requiredKeys,
                    "bundled resource lang/" + requestedFileName
            )) {
                plugin.getLogger().warning("Il file lingua esterno "
                        + selectedPath.getFileName()
                        + " non e valido. Uso la copia interna dal jar.");
                return new LoadedLanguage(bundledMessages.get(), requestedFileName + " (bundled fallback)");
            }

            plugin.getLogger().warning("Il file lingua " + selectedPath.getFileName()
                    + " non e valido e non esiste un fallback valido per la stessa lingua. "
                    + "Uso " + LanguageFileSupport.DEFAULT_LANGUAGE_FILE + ".");
            return new LoadedLanguage(defaultMessages, LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (bundled default)");
        }

        Optional<Map<String, String>> bundledMessages = loadOptionalBundledMessages(requestedFileName);
        if (bundledMessages.isPresent()) {
            copyBundledLanguageIfMissing(langDirectory, requestedFileName);
            if (validateConfiguration(bundledMessages.get(), requiredKeys, "bundled resource lang/" + requestedFileName)) {
                return new LoadedLanguage(bundledMessages.get(), requestedFileName + " (bundled)");
            }
        }

        if (!requestedFileName.equals(LanguageFileSupport.DEFAULT_LANGUAGE_FILE)) {
            plugin.getLogger().warning("Lingua " + requestedFileName
                    + " non trovata nella cartella "
                    + langDirectory.toAbsolutePath()
                    + " e nemmeno nel jar. Uso "
                    + LanguageFileSupport.DEFAULT_LANGUAGE_FILE + ".");
        }
        return new LoadedLanguage(defaultMessages, LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (bundled default)");
    }

    private void copyBundledLanguageIfMissing(Path langDirectory, String fileName) throws IOException {
        if (LanguageFileSupport.findLanguageFile(langDirectory, fileName).isPresent()) {
            return;
        }

        Optional<InputStream> resourceStream = openBundledResource(fileName);
        if (resourceStream.isEmpty()) {
            return;
        }

        Path targetPath = langDirectory.resolve(fileName);
        try (InputStream inputStream = resourceStream.get()) {
            Files.copy(inputStream, targetPath);
        }
    }

    private Map<String, String> loadBundledMessages(String fileName) throws IOException {
        return loadOptionalBundledMessages(fileName)
                .orElseThrow(() -> new IOException("Risorsa lang/" + fileName + " mancante nel jar."));
    }

    private Optional<Map<String, String>> loadOptionalBundledMessages(String fileName) throws IOException {
        Optional<InputStream> resourceStream = openBundledResource(fileName);
        if (resourceStream.isEmpty()) {
            return Optional.empty();
        }

        try (InputStream inputStream = resourceStream.get();
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            return Optional.of(loadMessages(reader));
        }
    }

    private Optional<InputStream> openBundledResource(String fileName) {
        return Optional.ofNullable(plugin.getResource("lang/" + fileName));
    }

    private Map<String, String> loadFileMessages(Path filePath) throws IOException {
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return loadMessages(reader);
        }
    }

    private Map<String, String> loadMessages(Reader reader) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(reader);
        Map<String, String> messages = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            Object value = yaml.get(key);
            if (value == null || value instanceof ConfigurationSection) {
                continue;
            }
            messages.put(key, String.valueOf(value));
        }
        return Collections.unmodifiableMap(messages);
    }

    private boolean validateConfiguration(Map<String, String> messages, Set<String> requiredKeys, String sourceDescription) {
        Set<String> missingKeys = new TreeSet<>(requiredKeys);
        missingKeys.removeAll(messages.keySet());

        Set<String> extraKeys = new TreeSet<>(messages.keySet());
        extraKeys.removeAll(requiredKeys);

        if (!extraKeys.isEmpty()) {
            plugin.getLogger().warning("Il file lingua " + sourceDescription
                    + " contiene key sconosciute: "
                    + LanguageFileSupport.summarizeKeys(extraKeys, LOGGED_KEY_LIMIT));
        }

        if (!missingKeys.isEmpty()) {
            plugin.getLogger().severe("Il file lingua " + sourceDescription
                    + " e incompleto. Key mancanti: "
                    + LanguageFileSupport.summarizeKeys(missingKeys, LOGGED_KEY_LIMIT));
            return false;
        }

        return true;
    }

    private String string(String key, String fallback) {
        String value = configuration.get(key);
        return value == null ? fallback : value;
    }

    private record LoadedLanguage(Map<String, String> messages, String description) {
    }
}
