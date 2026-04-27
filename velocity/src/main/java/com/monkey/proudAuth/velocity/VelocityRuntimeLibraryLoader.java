package com.monkey.proudAuth.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

final class VelocityRuntimeLibraryLoader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final String RUNTIME_LIBRARIES_RESOURCE = "proudauth-velocity-runtime-libraries.properties";

    private final Logger logger;
    private final Path dataDirectory;

    VelocityRuntimeLibraryLoader(Logger logger, Path dataDirectory) {
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    URLClassLoader createClassLoader(Class<?> pluginClass) {
        try {
            Path libsDirectory = dataDirectory.resolve("runtime-libs");
            Files.createDirectories(libsDirectory);

            List<URL> urls = new ArrayList<>();
            urls.add(pluginClass.getProtectionDomain().getCodeSource().getLocation().toURI().toURL());

            for (LibraryCoordinate coordinate : runtimeLibraries(pluginClass)) {
                Path target = libsDirectory.resolve(coordinate.fileName());
                if (Files.notExists(target)) {
                    download(coordinate.downloadUri(), target);
                    logger.info("Scaricata libreria runtime {}.", coordinate.fileName());
                }
                urls.add(target.toUri().toURL());
            }

            return new ChildFirstClassLoader(
                    urls.toArray(URL[]::new),
                    pluginClass.getClassLoader(),
                    List.of(
                            "com.monkey.proudAuth.",
                            "net.bytebuddy.",
                            "com.zaxxer.hikari.",
                            "org.mindrot.jbcrypt.",
                            "dev.samstevens.totp.",
                            "com.mysql.",
                            "org.yaml.snakeyaml."
                    )
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Impossibile preparare le librerie runtime di ProudAuth su Velocity.", exception);
        }
    }

    private void download(URI uri, Path target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        connection.setReadTimeout((int) READ_TIMEOUT.toMillis());
        connection.setRequestProperty("User-Agent", "ProudAuth-Velocity-LibraryLoader");

        Path temporaryTarget = target.resolveSibling(target.getFileName() + ".part");
        try (InputStream inputStream = connection.getInputStream()) {
            Files.copy(inputStream, temporaryTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporaryTarget, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryTarget);
            connection.disconnect();
        }
    }

    private List<LibraryCoordinate> runtimeLibraries(Class<?> pluginClass) throws IOException {
        try (InputStream inputStream = pluginClass.getClassLoader().getResourceAsStream(RUNTIME_LIBRARIES_RESOURCE)) {
            if (inputStream == null) {
                throw new IOException("Risorsa runtime libraries mancante nel jar: " + RUNTIME_LIBRARIES_RESOURCE);
            }

            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            List<String> orderedKeys = properties.stringPropertyNames().stream()
                    .filter(name -> name.startsWith("library."))
                    .sorted(Comparator.comparingInt(this::libraryIndex))
                    .toList();

            if (orderedKeys.isEmpty()) {
                throw new IOException("Nessuna libreria runtime definita in " + RUNTIME_LIBRARIES_RESOURCE);
            }

            List<LibraryCoordinate> coordinates = new ArrayList<>(orderedKeys.size());
            for (String key : orderedKeys) {
                coordinates.add(parseCoordinate(properties.getProperty(key), key));
            }
            return coordinates;
        }
    }

    private int libraryIndex(String key) {
        try {
            return Integer.parseInt(key.substring("library.".length()));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private LibraryCoordinate parseCoordinate(String rawCoordinate, String key) throws IOException {
        if (rawCoordinate == null || rawCoordinate.isBlank()) {
            throw new IOException("Coordinata libreria runtime vuota per " + key);
        }

        String[] parts = rawCoordinate.split(":");
        if (parts.length != 3) {
            throw new IOException("Coordinata libreria runtime non valida per " + key + ": " + rawCoordinate);
        }
        return new LibraryCoordinate(parts[0], parts[1], parts[2]);
    }

    private record LibraryCoordinate(String groupId, String artifactId, String version) {

        String fileName() {
            return artifactId + "-" + version + ".jar";
        }

        URI downloadUri() {
            return URI.create("https://repo1.maven.org/maven2/"
                    + groupId.replace('.', '/')
                    + "/"
                    + artifactId
                    + "/"
                    + version
                    + "/"
                    + fileName());
        }
    }

    private static final class ChildFirstClassLoader extends URLClassLoader {

        private final List<String> childFirstPrefixes;

        private ChildFirstClassLoader(URL[] urls, ClassLoader parent, List<String> childFirstPrefixes) {
            super(urls, parent);
            this.childFirstPrefixes = childFirstPrefixes;
        }

        @Override
        protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (shouldLoadChildFirst(name)) {
                Class<?> loadedClass = findLoadedClass(name);
                if (loadedClass == null) {
                    try {
                        loadedClass = findClass(name);
                    } catch (ClassNotFoundException ignored) {
                        loadedClass = super.loadClass(name, false);
                    }
                }
                if (resolve) {
                    resolveClass(loadedClass);
                }
                return loadedClass;
            }

            return super.loadClass(name, resolve);
        }

        private boolean shouldLoadChildFirst(String className) {
            return childFirstPrefixes.stream().anyMatch(className::startsWith);
        }
    }
}
