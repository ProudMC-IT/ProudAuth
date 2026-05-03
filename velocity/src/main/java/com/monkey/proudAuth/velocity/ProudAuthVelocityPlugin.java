package com.monkey.proudAuth.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
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

@Plugin(
        id = "proudauth",
        name = "ProudAuth",
        version = ProudAuthBuildConstants.VERSION,
        authors = {"MonkeyMoon104"},
        description = "Proxy companion per ProudAuth"
)
public final class ProudAuthVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private Object delegate;
    private Method initializeMethod;
    private Method shutdownMethod;
    private URLClassLoader runtimeClassLoader;

    @Inject
    public ProudAuthVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            VelocityRuntimeLibraryLoader libraryLoader = new VelocityRuntimeLibraryLoader(logger, dataDirectory);
            runtimeClassLoader = libraryLoader.createClassLoader(getClass());
            ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(runtimeClassLoader);
            try {
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
}
