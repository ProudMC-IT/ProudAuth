package com.monkey.proudAuth.common.monitor;

import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class MonitorIdentityStore {

    private static final String FILE_NAME = ".monitor-identity.properties";

    private final Path file;
    private final ProudAuthConsoleLogger logger;
    private final MonitorSessionIdGenerator generator = new MonitorSessionIdGenerator();

    public MonitorIdentityStore(Path dataDirectory, ProudAuthConsoleLogger logger) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
    }

    public MonitorIdentity loadOrCreate() {
        try {
            Files.createDirectories(file.getParent());

            if (Files.exists(file)) {
                return load();
            }

            MonitorIdentity identity = new MonitorIdentity(
                    generator.generateShortId("net"),
                    generator.generateShortId("proxy")
            );

            save(identity);
            return identity;
        } catch (Exception exception) {
            logger.error("Errore durante inizializzazione identità monitor.", exception);
            return new MonitorIdentity(
                    generator.generateShortId("net"),
                    generator.generateShortId("proxy")
            );
        }
    }

    private MonitorIdentity load() throws IOException {
        Properties properties = new Properties();

        try (InputStream inputStream = Files.newInputStream(file)) {
            properties.load(inputStream);
        }

        String networkId = properties.getProperty("network-id");
        String proxyId = properties.getProperty("proxy-id");

        if (networkId == null || networkId.isBlank() || proxyId == null || proxyId.isBlank()) {
            MonitorIdentity identity = new MonitorIdentity(
                    generator.generateShortId("net"),
                    generator.generateShortId("proxy")
            );
            save(identity);
            return identity;
        }

        return new MonitorIdentity(networkId, proxyId);
    }

    private void save(MonitorIdentity identity) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("network-id", identity.networkId());
        properties.setProperty("proxy-id", identity.proxyId());

        try (OutputStream outputStream = Files.newOutputStream(file)) {
            properties.store(outputStream, "ProudAuth Monitor Identity");
        }
    }
}