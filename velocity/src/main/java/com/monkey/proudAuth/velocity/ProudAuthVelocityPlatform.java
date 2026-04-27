package com.monkey.proudAuth.velocity;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.premium.impl.MojangPremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.common.storage.impl.MySQLStorage;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.commands.ProudAuthVelocityCommand;
import com.monkey.proudAuth.velocity.config.VelocityConfigLoader;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.config.VelocityPluginSettings;
import com.monkey.proudAuth.velocity.listeners.VelocityGameProfileListener;
import com.monkey.proudAuth.velocity.listeners.VelocityPreLoginListener;
import com.monkey.proudAuth.velocity.listeners.VelocityServerTransitionListener;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.security.VelocityRiskCsvExporter;
import com.monkey.proudAuth.velocity.security.VelocitySecurityInspectorService;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.monkey.proudAuth.velocity.session.VelocityWhitelistEnforcementStore;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

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
    private VelocityNetworkGuardService networkGuardService;
    private VelocitySecurityInspectorService securityInspectorService;
    private VelocityRiskCsvExporter riskCsvExporter;
    private Instant lastAutoExportAt;
    private ScheduledTask maintenanceTask;
    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore = new VelocityWhitelistEnforcementStore();
    private final VelocityResolvedPlayerStore resolvedPlayerStore = new VelocityResolvedPlayerStore();

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
            settings = configLoader.settings();
            lang = new VelocityLang(pluginOwner, dataDirectory, platformLogger);
            lang.reload(settings.language());
            storage = new MySQLStorage(settings.toCommonSettings());
            storage.init();
            premiumVerifier = new MojangPremiumVerifier(settings.toCommonSettings());
            bridgeService = new ProxyBridgeService(storage, settings.toCommonSettings());
            networkGuardService = new VelocityNetworkGuardService(
                    () -> storage,
                    () -> settings.guards(),
                    () -> settings.debugger(),
                    platformLogger
            );
            securityInspectorService = new VelocitySecurityInspectorService(() -> storage);
            riskCsvExporter = new VelocityRiskCsvExporter(securityInspectorService, dataDirectory.resolve("reports"));
            lastAutoExportAt = Instant.EPOCH;
            platformLogger.banner(
                    "ProudAuth v1.0.1",
                    "Platform: Velocity proxy",
                    "Language: " + lang.activeLanguageDescription(),
                    "Bridge: " + (settings.bridge().enabled() ? "enabled (" + settings.bridge().mode() + ")" : "disabled"),
                    "Premium resolution: enabled=" + settings.premium().enabled() + ", api-timeout-ms=" + settings.premium().apiTimeoutMs(),
                    "Debugger: " + settings.debugger().summary()
            );
            debugEvent(DebugChannel.COMMAND_FLOW, "velocity_init_config",
                    "bridge_enabled", settings.bridge().enabled(),
                    "bridge_mode", settings.bridge().mode(),
                    "premium_enabled", settings.premium().enabled(),
                    "premium_api_timeout_ms", settings.premium().apiTimeoutMs());

            VelocityBackendJoinProbeService backendJoinProbeService = new VelocityBackendJoinProbeService(
                    () -> storage,
                    () -> settings.bridge(),
                    () -> settings.debugger(),
                    platformLogger
            );

            proxyServer.getEventManager().register(pluginOwner, new VelocityPreLoginListener(
                    () -> storage,
                    () -> lang,
                    () -> settings.debugger(),
                    backendJoinProbeService,
                    networkGuardService,
                    whitelistEnforcementStore,
                    platformLogger
            ));
            proxyServer.getEventManager().register(pluginOwner, new VelocityGameProfileListener(
                    () -> premiumVerifier,
                    () -> bridgeService,
                    () -> lang,
                    () -> settings.debugger(),
                    networkGuardService,
                    whitelistEnforcementStore,
                    resolvedPlayerStore,
                    platformLogger
            ));
            proxyServer.getEventManager().register(pluginOwner, new VelocityServerTransitionListener(
                    resolvedPlayerStore,
                    () -> bridgeService,
                    () -> settings.debugger(),
                    platformLogger
            ));

            CommandMeta commandMeta = proxyServer.getCommandManager()
                    .metaBuilder("paproxy")
                    .build();
            proxyServer.getCommandManager().register(commandMeta, new ProudAuthVelocityCommand(
                    () -> lang,
                    () -> securityInspectorService,
                    () -> riskCsvExporter,
                    () -> storage,
                    this::reloadPluginState
            ));
            scheduleMaintenance();
            platformLogger.success("Velocity proxy enabled.");
        });
    }

    public void shutdown() {
        withRuntimeContext(() -> {
            if (maintenanceTask != null) {
                maintenanceTask.cancel();
                maintenanceTask = null;
            }
            if (storage != null) {
                storage.close();
            }
            platformLogger.info("Velocity proxy disabled.");
        });
    }

    public void reloadPluginState() {
        withRuntimeContext(() -> {
            configLoader.reload();
            settings = configLoader.settings();
            lang.reload(settings.language());
            storage.reload(settings.toCommonSettings());
            premiumVerifier.reload(settings.toCommonSettings());
            bridgeService.reload(settings.toCommonSettings());
            lastAutoExportAt = Instant.EPOCH;
            if (maintenanceTask != null) {
                maintenanceTask.cancel();
                maintenanceTask = null;
            }
            scheduleMaintenance();
            platformLogger.info("Reload completed. Language: "
                    + lang.activeLanguageDescription()
                    + ". Debugger: "
                    + settings.debugger().summary());
            debugEvent(DebugChannel.COMMAND_FLOW, "velocity_reload_config",
                    "bridge_enabled", settings.bridge().enabled(),
                    "bridge_mode", settings.bridge().mode(),
                    "premium_enabled", settings.premium().enabled(),
                    "premium_api_timeout_ms", settings.premium().apiTimeoutMs());
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

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        if (settings == null) {
            return;
        }
        platformLogger.debugEvent(settings.debugger(), channel, eventName, keyValues);
    }

    private void scheduleMaintenance() {
        maintenanceTask = proxyServer.getScheduler()
                .buildTask(pluginOwner, this::runMaintenance)
                .repeat(5, TimeUnit.MINUTES)
                .schedule();
    }

    private void runMaintenance() {
        withRuntimeContext(() -> {
            try {
                storage.deleteExpiredProxyAssertions().join();
                storage.deleteExpiredBackendJoinProbes().join();
                if (networkGuardService != null) {
                    networkGuardService.cleanupHistory();
                }
                runAutoRiskExportIfNeeded();
            } catch (Exception exception) {
                platformLogger.error("Errore durante la manutenzione Velocity.", exception);
            }
        });
    }

    private void runAutoRiskExportIfNeeded() {
        if (riskCsvExporter == null || settings == null || !settings.reports().autoExportEnabled()) {
            return;
        }

        Instant now = Instant.now();
        Duration interval = Duration.ofMinutes(settings.reports().autoExportIntervalMinutes());
        if (lastAutoExportAt != null && now.isBefore(lastAutoExportAt.plus(interval))) {
            return;
        }

        try {
            riskCsvExporter.export(
                    Duration.ofHours(settings.reports().autoExportWindowHours()),
                    settings.reports().autoExportLimit()
            );
            lastAutoExportAt = now;
        } catch (Exception exception) {
            platformLogger.error("Errore durante export CSV automatico rischio.", exception);
        }
    }
}
