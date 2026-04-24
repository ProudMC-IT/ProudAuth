package com.monkey.proudAuth.velocity.config;

import com.monkey.proudAuth.common.lang.LanguageFileSupport;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class VelocityLang {

    private static final int LOGGED_KEY_LIMIT = 10;

    private final Object plugin;
    private final Path dataDirectory;
    private final ProudAuthConsoleLogger logger;
    private final MiniMessage miniMessage;
    private final Yaml yaml;
    private volatile Map<String, String> configuration;
    private volatile String activeLanguageDescription;

    public VelocityLang(Object plugin, Path dataDirectory, ProudAuthConsoleLogger logger) {
        this.plugin = plugin;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.miniMessage = MiniMessage.miniMessage();
        this.yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        this.configuration = Collections.emptyMap();
        this.activeLanguageDescription = LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (bundled default)";
    }

    public void reload(String configuredLanguage) {
        try {
            Path langDirectory = dataDirectory.resolve("lang");
            Files.createDirectories(langDirectory);

            Map<String, String> defaultMessages = loadBundledMessages(LanguageFileSupport.DEFAULT_LANGUAGE_FILE);
            copyBundledLanguageIfMissing(langDirectory, LanguageFileSupport.DEFAULT_LANGUAGE_FILE);

            String requestedFileName = LanguageFileSupport.normalizeConfiguredLanguage(configuredLanguage);
            LoadedLanguage loadedLanguage = resolveConfiguredLanguage(langDirectory, requestedFileName, defaultMessages);
            this.configuration = loadedLanguage.messages();
            this.activeLanguageDescription = loadedLanguage.description();
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
                logger.warn("Il file lingua esterno " + selectedPath.getFileName() + " non e valido. Uso la copia interna dal jar.");
                return new LoadedLanguage(bundledMessages.get(), requestedFileName + " (bundled fallback)");
            }

            logger.warn("Il file lingua " + selectedPath.getFileName()
                    + " non e valido e non esiste un fallback valido per la stessa lingua. Uso "
                    + LanguageFileSupport.DEFAULT_LANGUAGE_FILE + ".");
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
            logger.warn("Lingua " + requestedFileName
                    + " non trovata nella cartella "
                    + langDirectory.toAbsolutePath()
                    + " e nemmeno nel jar. Uso "
                    + LanguageFileSupport.DEFAULT_LANGUAGE_FILE + ".");
        }
        return new LoadedLanguage(defaultMessages, LanguageFileSupport.DEFAULT_LANGUAGE_FILE + " (bundled default)");
    }

    private String string(String key, String fallback) {
        String value = configuration.get(key);
        return value == null ? fallback : value;
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
        return Optional.ofNullable(plugin.getClass().getClassLoader().getResourceAsStream("lang/" + fileName));
    }

    private Map<String, String> loadFileMessages(Path filePath) throws IOException {
        try (Reader reader = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            return loadMessages(reader);
        }
    }

    private Map<String, String> loadMessages(Reader reader) {
        Object loaded = yaml.load(reader);
        return normalizeConfiguration(loaded);
    }

    private Map<String, String> normalizeConfiguration(Object loaded) {
        if (!(loaded instanceof Map<?, ?> rawMap)) {
            return Collections.emptyMap();
        }

        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() instanceof String key) {
                Object value = entry.getValue();
                if (value != null) {
                    normalized.put(key, String.valueOf(value));
                }
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private boolean validateConfiguration(Map<String, String> messages, Set<String> requiredKeys, String sourceDescription) {
        Set<String> missingKeys = new TreeSet<>(requiredKeys);
        missingKeys.removeAll(messages.keySet());

        Set<String> extraKeys = new TreeSet<>(messages.keySet());
        extraKeys.removeAll(requiredKeys);

        if (!extraKeys.isEmpty()) {
            logger.warn("Il file lingua " + sourceDescription
                    + " contiene key sconosciute: "
                    + LanguageFileSupport.summarizeKeys(extraKeys, LOGGED_KEY_LIMIT));
        }

        if (!missingKeys.isEmpty()) {
            logger.error("Il file lingua " + sourceDescription
                    + " e incompleto. Key mancanti: "
                    + LanguageFileSupport.summarizeKeys(missingKeys, LOGGED_KEY_LIMIT));
            return false;
        }

        return true;
    }

    private record LoadedLanguage(Map<String, String> messages, String description) {
    }
}
