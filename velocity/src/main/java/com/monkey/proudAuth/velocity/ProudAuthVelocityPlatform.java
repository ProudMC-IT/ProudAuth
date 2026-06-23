package com.monkey.proudAuth.velocity;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfigCodec;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.network.ProudAuthNetworkChannel;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.premium.impl.MojangPremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.common.storage.impl.MySQLStorage;
import com.monkey.proudAuth.velocity.access.VelocityDelegatedAccessService;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.commands.ProudAccessVelocityCommand;
import com.monkey.proudAuth.velocity.commands.ProudAuthVelocityCommand;
import com.monkey.proudAuth.velocity.commands.ProudMonitorVelocityCommand;
import com.monkey.proudAuth.velocity.config.VelocityConfigLoader;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.instrumentation.VelocityOnlineModeDisconnectInstrumentation;
import com.monkey.proudAuth.velocity.listeners.VelocityAuthSyncListener;
import com.monkey.proudAuth.velocity.listeners.VelocityGameProfileListener;
import com.monkey.proudAuth.velocity.listeners.VelocityPreLoginListener;
import com.monkey.proudAuth.velocity.listeners.VelocityServerTransitionListener;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorAuthStateStore;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorListener;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorService;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.security.VelocityRiskCsvExporter;
import com.monkey.proudAuth.velocity.security.VelocitySecurityInspectorService;
import com.monkey.proudAuth.velocity.session.VelocityPendingPremiumAuthStore;
import com.monkey.proudAuth.velocity.session.VelocityPremiumClaimFailureStore;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.monkey.proudAuth.velocity.session.VelocityWhitelistEnforcementStore;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public final class ProudAuthVelocityPlatform {

    private final Object pluginOwner;
    private final ProxyServer proxyServer;
    private final ProudAuthConsoleLogger platformLogger;
    private final Path dataDirectory;
    private final ProudAuthNetworkConfigCodec networkConfigCodec = new ProudAuthNetworkConfigCodec();

    private VelocityConfigLoader configLoader;
    private VelocityLang lang;
    private ProudAuthNetworkConfig settings;
    private StorageProvider storage;
    private PremiumVerifier premiumVerifier;
    private IdentityClaimService identityClaimService;
    private ProxyBridgeService bridgeService;
    private VelocityNetworkGuardService networkGuardService;
    private VelocitySecurityInspectorService securityInspectorService;
    private VelocityRiskCsvExporter riskCsvExporter;
    private VelocityOnlineModeDisconnectInstrumentation disconnectInstrumentation;
    private VelocityMonitorService monitorService;
    private Instant lastAutoExportAt;
    private ScheduledTask maintenanceTask;
    private ScheduledTask configWatchTask;
    private volatile boolean proudAccessAvailable;

    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore = new VelocityWhitelistEnforcementStore();
    private final VelocityResolvedPlayerStore resolvedPlayerStore = new VelocityResolvedPlayerStore();
    private final VelocityPendingPremiumAuthStore pendingPremiumAuthStore = new VelocityPendingPremiumAuthStore();
    private final VelocityPremiumClaimFailureStore premiumClaimFailureStore = new VelocityPremiumClaimFailureStore();
    private final VelocityMonitorAuthStateStore monitorAuthStateStore = new VelocityMonitorAuthStateStore();
    private final MinecraftChannelIdentifier networkChannelIdentifier = MinecraftChannelIdentifier.from(ProudAuthNetworkChannel.CHANNEL_ID);

    public ProudAuthVelocityPlatform(Object pluginOwner, ProxyServer proxyServer, org.slf4j.Logger logger, Path dataDirectory) {
        this.pluginOwner = pluginOwner;
        this.proxyServer = proxyServer;

        org.slf4j.Logger velocityLogger = LoggerFactory.getLogger(
                ProudAuthConsoleLogger.colorLoggerName("ProudAuth/Velocity")
        );

        this.platformLogger = new ProudAuthConsoleLogger(
                "ProudAuth/Velocity",
                velocityLogger::info,
                velocityLogger::warn,
                (message, throwable) -> {
                    if (throwable == null) {
                        velocityLogger.error(message);
                    } else {
                        velocityLogger.error(message, throwable);
                    }
                },
                false
        );

        this.dataDirectory = dataDirectory;
    }

    public void initialize() {
        withRuntimeContext(() -> {
            configLoader = new VelocityConfigLoader(pluginOwner, dataDirectory);
            settings = configLoader.settings();
            lang = new VelocityLang(pluginOwner, dataDirectory, platformLogger);
            lang.reload(settings.language());
            storage = new MySQLStorage(settings.toBackendSettings(settings.database()), platformLogger);
            storage.init();
            publishActiveNetworkConfig("velocity-startup");
            premiumVerifier = new MojangPremiumVerifier(settings.toBackendSettings(settings.database()));
            identityClaimService = new IdentityClaimService(storage, settings.toBackendSettings(settings.database()));
            bridgeService = new ProxyBridgeService(storage, settings.toBackendSettings(settings.database()));
            networkGuardService = new VelocityNetworkGuardService(
                    () -> storage,
                    () -> settings.proxy().guards(),
                    () -> settings.debugger(),
                    platformLogger
            );
            securityInspectorService = new VelocitySecurityInspectorService(() -> storage);
            riskCsvExporter = new VelocityRiskCsvExporter(securityInspectorService, dataDirectory.resolve("reports"));
            disconnectInstrumentation = new VelocityOnlineModeDisconnectInstrumentation(
                    platformLogger,
                    identityClaimService,
                    pendingPremiumAuthStore,
                    premiumClaimFailureStore
            );
            lastAutoExportAt = Instant.EPOCH;

            platformLogger.banner(
                    "ProudAuth v" + ProudAuthBuildConstants.VERSION,
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

            proudAccessAvailable = validateProudAccessAvailability("startup");

            disconnectInstrumentation.updateMessages(
                    settings.proxy().onlineModeDeniedMessage(),
                    settings.proxy().claimFailedDeniedMessage()
            );
            disconnectInstrumentation.install();
            proxyServer.getChannelRegistrar().register(networkChannelIdentifier);

            VelocityBackendJoinProbeService backendJoinProbeService = new VelocityBackendJoinProbeService(
                    () -> storage,
                    () -> settings,
                    () -> settings.debugger(),
                    platformLogger
            );

            monitorService = new VelocityMonitorService(
                    pluginOwner,
                    proxyServer,
                    dataDirectory,
                    platformLogger,
                    resolvedPlayerStore,
                    monitorAuthStateStore,
                    () -> lang,
                    () -> settings.monitor()
            );

            VelocityDelegatedAccessService delegatedAccessService = new VelocityDelegatedAccessService(
                    pluginOwner,
                    proxyServer,
                    () -> storage,
                    () -> premiumVerifier,
                    resolvedPlayerStore,
                    this::resolvedRouting,
                    this::routingForServer,
                    this::proxyRegionId,
                    this::regionIdForServer,
                    () -> settings.paAccess().premiumTargets()
            );
            proxyServer.getEventManager().register(pluginOwner, delegatedAccessService);
            warnIfPremiumTargetsEnabled();

            proxyServer.getEventManager().register(pluginOwner, new VelocityPreLoginListener(
                    () -> storage,
                    () -> premiumVerifier,
                    identityClaimService,
                    () -> lang,
                    () -> settings.toBackendSettings(settings.database()),
                    () -> settings.debugger(),
                    this::resolvedRouting,
                    this::routingForServer,
                    this::proxyRegionId,
                    backendJoinProbeService,
                    networkGuardService,
                    whitelistEnforcementStore,
                    pendingPremiumAuthStore,
                    premiumClaimFailureStore,
                    platformLogger
            ));

            proxyServer.getEventManager().register(pluginOwner, new VelocityGameProfileListener(
                    () -> premiumVerifier,
                    identityClaimService,
                    () -> bridgeService,
                    () -> lang,
                    () -> settings.debugger(),
                    networkGuardService,
                    whitelistEnforcementStore,
                    pendingPremiumAuthStore,
                    premiumClaimFailureStore,
                    resolvedPlayerStore,
                    delegatedAccessService,
                    () -> settings.paAccess(),
                    platformLogger
            ));

            proxyServer.getEventManager().register(pluginOwner, new VelocityServerTransitionListener(
                    pluginOwner,
                    proxyServer,
                    resolvedPlayerStore,
                    premiumClaimFailureStore,
                    () -> bridgeService,
                    this::resolvedRouting,
                    this::routingForServer,
                    this::proxyRegionId,
                    this::regionIdForServer,
                    this::proxyNodeId,
                    () -> lang,
                    () -> settings.debugger(),
                    platformLogger
            ));

            proxyServer.getEventManager().register(pluginOwner, new VelocityAuthSyncListener(
                    pluginOwner,
                    proxyServer,
                    resolvedPlayerStore,
                    this::resolvedRouting,
                    this::routingForServer,
                    () -> lang,
                    () -> settings.debugger(),
                    platformLogger,
                    monitorAuthStateStore,
                    () -> monitorService
            ));

            proxyServer.getEventManager().register(pluginOwner, new VelocityMonitorListener(monitorService));

            CommandMeta commandMeta = proxyServer.getCommandManager()
                    .metaBuilder("paproxy")
                    .build();

            proxyServer.getCommandManager().register(commandMeta, new ProudAuthVelocityCommand(
                    () -> lang,
                    () -> securityInspectorService,
                    () -> riskCsvExporter,
                    () -> storage,
                    () -> monitorService,
                    this::reloadPluginState
            ));

            CommandMeta accessCommandMeta = proxyServer.getCommandManager()
                    .metaBuilder("proudaccess")
                    .aliases("paaccess")
                    .build();

            proxyServer.getCommandManager().register(accessCommandMeta, new ProudAccessVelocityCommand(
                    delegatedAccessService,
                    () -> lang,
                    () -> settings.paAccess().history(),
                    () -> proudAccessAvailable
            ));

            CommandMeta monitorCommandMeta = proxyServer.getCommandManager()
                    .metaBuilder("proudmonitor")
                    .aliases("pmonitor", "pmon")
                    .build();

            proxyServer.getCommandManager().register(monitorCommandMeta, new ProudMonitorVelocityCommand(
                    () -> lang,
                    () -> monitorService
            ));

            scheduleMaintenance();
            scheduleConfigWatch();

            platformLogger.success("Velocity proxy enabled.");
        });
    }

    public void shutdown() {
        withRuntimeContext(() -> {
            if (maintenanceTask != null) {
                maintenanceTask.cancel();
                maintenanceTask = null;
            }

            if (configWatchTask != null) {
                configWatchTask.cancel();
                configWatchTask = null;
            }

            if (disconnectInstrumentation != null) {
                disconnectInstrumentation.shutdown();
                disconnectInstrumentation = null;
            }

            if (monitorService != null) {
                monitorService.shutdown();
                monitorService = null;
            }

            proxyServer.getChannelRegistrar().unregister(networkChannelIdentifier);

            if (storage != null) {
                storage.close();
            }

            platformLogger.info("Velocity proxy disabled.");
        });
    }

    public void reloadPluginState() {
        withRuntimeContext(() -> reloadFromDisk("velocity-reload"));
    }

    private void reloadFromDisk(String updatedBy) {
        configLoader.reload();
        settings = configLoader.settings();
        lang.reload(settings.language());
        storage.reload(settings.toBackendSettings(settings.database()));
        publishActiveNetworkConfig(updatedBy);
        premiumVerifier.reload(settings.toBackendSettings(settings.database()));
        identityClaimService.reload(settings.toBackendSettings(settings.database()));
        bridgeService.reload(settings.toBackendSettings(settings.database()));
        lastAutoExportAt = Instant.EPOCH;
        proudAccessAvailable = validateProudAccessAvailability(updatedBy);
        warnIfPremiumTargetsEnabled();

        if (disconnectInstrumentation != null) {
            disconnectInstrumentation.updateMessages(
                    settings.proxy().onlineModeDeniedMessage(),
                    settings.proxy().claimFailedDeniedMessage()
            );
        }

        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }

        if (configWatchTask != null) {
            configWatchTask.cancel();
            configWatchTask = null;
        }

        scheduleMaintenance();
        scheduleConfigWatch();

        platformLogger.info("Reload completed. Language: "
                + lang.activeLanguageDescription()
                + ". Debugger: "
                + settings.debugger().summary());

        debugEvent(DebugChannel.COMMAND_FLOW, "velocity_reload_config",
                "bridge_enabled", settings.bridge().enabled(),
                "bridge_mode", settings.bridge().mode(),
                "premium_enabled", settings.premium().enabled(),
                "premium_api_timeout_ms", settings.premium().apiTimeoutMs());
    }

    private void publishActiveNetworkConfig(String updatedBy) {
        String document = networkConfigCodec.dump(settings, lang.exportMessages());
        String configHash = sha256(document);
        var snapshot = storage.saveActiveNetworkConfig(document, configHash, updatedBy, proxyNodeId()).join();

        debugEvent(DebugChannel.COMMAND_FLOW, "network_config_published",
                "version", snapshot.version(),
                "hash", snapshot.configHash(),
                "updated_by", updatedBy);
    }

    private String sha256(String document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(document.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 non disponibile", exception);
        }
    }

    private ProudAuthNetworkConfig.Routing resolvedRouting() {
        return settings.proxy().routingForRegion(proxyRegionId());
    }

    private ProudAuthNetworkConfig.Routing routingForServer(String serverName) {
        return settings.proxy().routingForServer(serverName, proxyRegionId());
    }

    private String regionIdForServer(String serverName) {
        return settings.proxy().regionIdForServer(serverName).orElseGet(this::proxyRegionId);
    }

    private String proxyRegionId() {
        return configLoader == null ? "" : configLoader.proxyRegionId();
    }

    private String proxyNodeId() {
        if (configLoader == null || configLoader.proxyNodeId().isBlank()) {
            return "velocity-proxy";
        }
        return configLoader.proxyNodeId();
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

    private void scheduleConfigWatch() {
        configWatchTask = proxyServer.getScheduler()
                .buildTask(pluginOwner, this::watchConfigChanges)
                .repeat(Math.max(1, configLoader.fileWatchIntervalSeconds()), TimeUnit.SECONDS)
                .schedule();
    }

    private void warnIfPremiumTargetsEnabled() {
        if (settings == null
                || !proudAccessAvailable
                || !settings.paAccess().premiumTargets().enabled()
                || !settings.paAccess().premiumTargets().warnOnStartup()) {
            return;
        }

        platformLogger.warn("ProudAccess premium-targets is enabled. Delegated access into premium target accounts requires ProudX with player-info-forwarding-mode=\"proudx\" so backend profile-key forwarding can be suppressed only for validated delegated sessions.");
    }

    private boolean validateProudAccessAvailability(String phase) {
        if (settings == null || !settings.paAccess().enabled()) {
            platformLogger.info("ProudAccess disabled by config. phase=" + phase);
            return false;
        }

        ProudXRuntime runtime = detectProudXRuntime();
        if (!runtime.detected()) {
            platformLogger.error("ProudAccess disabled: ProudX runtime was not detected. "
                    + "Install/run the ProudX proxy jar and set player-info-forwarding-mode=\"proudx\". "
                    + "phase=" + phase + " reason=" + runtime.reason(), null);
            return false;
        }

        if (!"PROUDX".equalsIgnoreCase(runtime.forwardingMode())) {
            platformLogger.error("ProudAccess disabled: ProudX is running, but player-info-forwarding-mode is \""
                    + runtime.forwardingMode()
                    + "\" instead of \"proudx\". phase="
                    + phase, null);
            return false;
        }

        platformLogger.success("ProudAccess enabled. ProudX runtime detected with player-info-forwarding-mode=\"proudx\".");
        return true;
    }

    private ProudXRuntime detectProudXRuntime() {
        try {
            Object detected = proxyServer.getClass().getMethod("isProudX").invoke(proxyServer);
            if (!Boolean.TRUE.equals(detected)) {
                return new ProudXRuntime(false, "UNKNOWN", "marker_returned_false");
            }

            Object mode = proxyServer.getClass().getMethod("getProudXPlayerInfoForwardingMode").invoke(proxyServer);
            return new ProudXRuntime(true, String.valueOf(mode), "ok");
        } catch (NoSuchMethodException exception) {
            return new ProudXRuntime(false, "UNKNOWN", "missing_proudx_marker");
        } catch (ReflectiveOperationException | SecurityException exception) {
            return new ProudXRuntime(false, "UNKNOWN", exception.getClass().getSimpleName());
        }
    }

    private record ProudXRuntime(boolean detected, String forwardingMode, String reason) {
    }

    private void watchConfigChanges() {
        withRuntimeContext(() -> {
            boolean configChanged = configLoader.hasFileChanged();
            boolean languageChanged = lang != null && lang.hasLanguageFileChanged();

            if (!configChanged && !languageChanged) {
                return;
            }

            try {
                reloadFromDisk(languageChanged && !configChanged
                        ? "velocity-language-file-watch"
                        : "velocity-file-watch");
            } catch (Exception exception) {
                platformLogger.error("Errore durante reload automatico della config Velocity.", exception);
            }
        });
    }

    private void runMaintenance() {
        withRuntimeContext(() -> {
            try {
                storage.deleteExpiredProxyAssertions().join();
                storage.deleteExpiredBackendJoinProbes().join();
                storage.deleteExpiredPendingIdentityClaims().join();
                storage.deleteDelegatedAccessHistoryBefore(
                        Instant.now().minus(Duration.ofDays(settings.paAccess().history().retentionDays()))
                ).join();

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
        if (riskCsvExporter == null || settings == null || !settings.proxy().reports().autoExportEnabled()) {
            return;
        }

        Instant now = Instant.now();
        Duration interval = Duration.ofMinutes(settings.proxy().reports().autoExportIntervalMinutes());

        if (lastAutoExportAt != null && now.isBefore(lastAutoExportAt.plus(interval))) {
            return;
        }

        try {
            riskCsvExporter.export(
                    Duration.ofHours(settings.proxy().reports().autoExportWindowHours()),
                    settings.proxy().reports().autoExportLimit()
            );
            lastAutoExportAt = now;
        } catch (Exception exception) {
            platformLogger.error("Errore durante export CSV automatico rischio.", exception);
        }
    }
}
