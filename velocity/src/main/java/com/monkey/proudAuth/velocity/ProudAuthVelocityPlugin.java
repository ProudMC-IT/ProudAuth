package com.monkey.proudAuth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.*;
import java.util.Properties;

@Plugin(
        id = "proudauth",
        name = "ProudAuth",
        version = ProudAuthBuildConstants.VERSION,
        authors = {"MonkeyMoon104"},
        description = "Proxy companion per ProudAuth"
)
public final class ProudAuthVelocityPlugin {

    private static final String MYSQL_DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;

    private Object delegate;
    private Method initializeMethod;
    private Method shutdownMethod;
    private URLClassLoader runtimeClassLoader;
    private Driver registeredJdbcDriver;

    @Inject
    public ProudAuthVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(priority = 0)
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            VelocityRuntimeLibraryLoader libraryLoader = new VelocityRuntimeLibraryLoader(logger, dataDirectory);
            runtimeClassLoader = libraryLoader.createClassLoader(getClass());

            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(runtimeClassLoader);

            try {
                registerMysqlDriver(runtimeClassLoader);

                Class<?> delegateClass = Class.forName(
                        "com.monkey.proudAuth.velocity.ProudAuthVelocityPlatform",
                        true,
                        runtimeClassLoader
                );

                delegate = delegateClass
                        .getConstructor(Object.class, ProxyServer.class, Logger.class, Path.class)
                        .newInstance(this, proxyServer, logger, dataDirectory);

                initializeMethod = delegateClass.getMethod("initialize");
                shutdownMethod = delegateClass.getMethod("shutdown");

                initializeMethod.invoke(delegate);
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        } catch (Exception exception) {
            logger.error("Impossibile avviare ProudAuth su Velocity", exception);
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        try {
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();

            if (runtimeClassLoader != null) {
                Thread.currentThread().setContextClassLoader(runtimeClassLoader);
            }

            try {
                if (delegate != null && shutdownMethod != null) {
                    shutdownMethod.invoke(delegate);
                }

                unregisterMysqlDriver();

                if (runtimeClassLoader != null) {
                    runtimeClassLoader.close();
                }
            } finally {
                Thread.currentThread().setContextClassLoader(previousContextClassLoader);
            }
        } catch (Exception exception) {
            logger.error("Errore durante lo shutdown di ProudAuth su Velocity", exception);
        }
    }

    private void registerMysqlDriver(ClassLoader classLoader) throws Exception {
        if (registeredJdbcDriver != null) {
            return;
        }

        Class<?> driverClass = Class.forName(MYSQL_DRIVER_CLASS, true, classLoader);
        Driver realDriver = (Driver) driverClass.getDeclaredConstructor().newInstance();

        registeredJdbcDriver = new DriverShim(realDriver);
        DriverManager.registerDriver(registeredJdbcDriver);

        logger.info("Driver JDBC MySQL registrato per ProudAuth: {}", MYSQL_DRIVER_CLASS);
    }

    private void unregisterMysqlDriver() {
        if (registeredJdbcDriver == null) {
            return;
        }

        try {
            DriverManager.deregisterDriver(registeredJdbcDriver);
            logger.info("Driver JDBC MySQL deregistrato per ProudAuth.");
        } catch (SQLException exception) {
            logger.warn("Impossibile deregistrare il driver JDBC MySQL di ProudAuth.", exception);
        } finally {
            registeredJdbcDriver = null;
        }
    }

    private static final class DriverShim implements Driver {

        private final Driver delegate;

        private DriverShim(Driver delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return delegate.connect(url, info);
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return delegate.acceptsURL(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return delegate.getPropertyInfo(url, info);
        }

        @Override
        public int getMajorVersion() {
            return delegate.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return delegate.getMinorVersion();
        }

        @Override
        public boolean jdbcCompliant() {
            return delegate.jdbcCompliant();
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }
    }
}
