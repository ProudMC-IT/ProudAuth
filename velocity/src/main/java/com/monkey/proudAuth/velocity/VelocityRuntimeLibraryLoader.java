package com.monkey.proudAuth.velocity;

import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class VelocityRuntimeLibraryLoader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

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

            for (LibraryCoordinate coordinate : runtimeLibraries()) {
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

    private List<LibraryCoordinate> runtimeLibraries() {
        return List.of(
                new LibraryCoordinate("com.zaxxer", "HikariCP", "7.0.2"),
                new LibraryCoordinate("org.mindrot", "jbcrypt", "0.4"),
                new LibraryCoordinate("dev.samstevens.totp", "totp", "1.7.1"),
                new LibraryCoordinate("commons-codec", "commons-codec", "1.13"),
                new LibraryCoordinate("com.google.zxing", "core", "3.4.0"),
                new LibraryCoordinate("com.google.zxing", "javase", "3.4.0"),
                new LibraryCoordinate("com.beust", "jcommander", "1.72"),
                new LibraryCoordinate("com.mysql", "mysql-connector-j", "9.6.0"),
                new LibraryCoordinate("com.google.protobuf", "protobuf-java", "4.31.1"),
                new LibraryCoordinate("org.yaml", "snakeyaml", "2.6"),
                new LibraryCoordinate("net.bytebuddy", "byte-buddy", "1.18.2"),
                new LibraryCoordinate("net.bytebuddy", "byte-buddy-agent", "1.18.2")
        );
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
