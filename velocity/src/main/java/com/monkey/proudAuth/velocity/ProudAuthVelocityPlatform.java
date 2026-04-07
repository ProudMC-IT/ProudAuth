package com.monkey.proudAuth.velocity;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
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
    private final ProudAuthConsoleLogger platformLogger;
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
        this.platformLogger = new ProudAuthConsoleLogger(
                "ProudAuth/Velocity",
                logger::info,
                logger::warn,
                (message, throwable) -> {
                    if (throwable == null) {
                        logger.error(message);
                    } else {
                        logger.error(message, throwable);
                    }
                }
        );
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
            platformLogger.banner(
                    "ProudAuth v1.0.0",
                    "Platform: Velocity proxy",
                    "Bridge: " + (settings.bridge().enabled() ? "enabled (" + settings.bridge().mode() + ")" : "disabled"),
                    "Rewrite game profile: " + settings.premium().rewriteGameProfile(),
                    "Debugger: " + settings.debugger().summary()
            );
            debug(DebugChannel.COMMAND_FLOW, "Velocity init bridgeEnabled=%s bridgeMode=%s rewriteGameProfile=%s",
                    settings.bridge().enabled(),
                    settings.bridge().mode(),
                    settings.premium().rewriteGameProfile());

            proxyServer.getEventManager().register(pluginOwner, new VelocityPreLoginListener(() -> storage, () -> lang, () -> settings.debugger(), platformLogger));
            proxyServer.getEventManager().register(pluginOwner, new VelocityGameProfileListener(
                    () -> premiumVerifier,
                    () -> bridgeService,
                    () -> settings.premium().rewriteGameProfile(),
                    () -> settings.debugger(),
                    platformLogger
            ));

            CommandMeta commandMeta = proxyServer.getCommandManager()
                    .metaBuilder("proudauth")
                    .aliases("proudauthproxy")
                    .build();
            proxyServer.getCommandManager().register(commandMeta, new ProudAuthVelocityCommand(() -> lang, this::reloadPluginState));
            platformLogger.success("Velocity proxy enabled.");
        });
    }

    public void shutdown() {
        withRuntimeContext(() -> {
            if (storage != null) {
                storage.close();
            }
            platformLogger.info("Velocity proxy disabled.");
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
            platformLogger.info("Reload completed. Debugger: " + settings.debugger().summary());
            debug(DebugChannel.COMMAND_FLOW, "Velocity reload bridgeEnabled=%s bridgeMode=%s rewriteGameProfile=%s",
                    settings.bridge().enabled(),
                    settings.bridge().mode(),
                    settings.premium().rewriteGameProfile());
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

    private void debug(DebugChannel channel, String template, Object... args) {
        if (settings == null) {
            return;
        }
        platformLogger.debug(settings.debugger(), channel, template, args);
    }
}
