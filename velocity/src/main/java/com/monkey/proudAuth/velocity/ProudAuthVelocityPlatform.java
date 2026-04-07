package com.monkey.proudAuth.velocity;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.premium.impl.MojangPremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.common.storage.impl.MySQLStorage;
import com.monkey.proudAuth.velocity.commands.ProudAuthVelocityCommand;
import com.monkey.proudAuth.velocity.config.VelocityConfigLoader;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.config.VelocityPluginSettings;
import com.monkey.proudAuth.velocity.listeners.VelocityGameProfileListener;
import com.monkey.proudAuth.velocity.listeners.VelocityPreLoginListener;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.file.Path;

public final class ProudAuthVelocityPlatform {

    private final Object pluginOwner;
    private final ProxyServer proxyServer;
    private final org.slf4j.Logger logger;
    private final Path dataDirectory;

    private VelocityConfigLoader configLoader;
    private VelocityLang lang;
    private VelocityPluginSettings settings;
    private StorageProvider storage;
    private PremiumVerifier premiumVerifier;
    private ProxyBridgeService bridgeService;

    public ProudAuthVelocityPlatform(Object pluginOwner, ProxyServer proxyServer, org.slf4j.Logger logger, Path dataDirectory) {
        this.pluginOwner = pluginOwner;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    public void initialize() {
        withRuntimeContext(() -> {
            configLoader = new VelocityConfigLoader(pluginOwner, dataDirectory);
            lang = new VelocityLang(pluginOwner, dataDirectory);
            settings = configLoader.settings();
            storage = new MySQLStorage(settings.toCommonSettings());
            storage.init();
            premiumVerifier = new MojangPremiumVerifier(settings.toCommonSettings());
            bridgeService = new ProxyBridgeService(storage, settings.toCommonSettings());
            debug("Velocity init debug=%s bridgeEnabled=%s bridgeMode=%s",
                    settings.debugEnabled(),
                    settings.bridge().enabled(),
                    settings.bridge().mode());

            proxyServer.getEventManager().register(pluginOwner, new VelocityPreLoginListener(() -> storage, () -> lang, () -> settings.debugEnabled(), logger));
            proxyServer.getEventManager().register(pluginOwner, new VelocityGameProfileListener(
                    () -> premiumVerifier,
                    () -> bridgeService,
                    () -> settings.debugEnabled(),
                    logger
            ));

            CommandMeta commandMeta = proxyServer.getCommandManager()
                    .metaBuilder("proudauth")
                    .aliases("proudauthproxy")
                    .build();
            proxyServer.getCommandManager().register(commandMeta, new ProudAuthVelocityCommand(() -> lang, this::reloadPluginState));
        });
    }

    public void shutdown() {
        withRuntimeContext(() -> {
            if (storage != null) {
                storage.close();
            }
        });
    }

    public void reloadPluginState() {
        withRuntimeContext(() -> {
            configLoader.reload();
            lang.reload();
            settings = configLoader.settings();
            storage.reload(settings.toCommonSettings());
            premiumVerifier.reload(settings.toCommonSettings());
            bridgeService.reload(settings.toCommonSettings());
            debug("Velocity reload debug=%s bridgeEnabled=%s bridgeMode=%s",
                    settings.debugEnabled(),
                    settings.bridge().enabled(),
                    settings.bridge().mode());
        });
    }

    private void withRuntimeContext(Runnable runnable) {
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            runnable.run();
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    private void debug(String template, Object... args) {
        if (settings == null || !settings.debugEnabled()) {
            return;
        }
        logger.info("[DEBUG] " + template.formatted(args));
    }
}
