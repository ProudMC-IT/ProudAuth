package com.monkey.proudAuth;

import com.monkey.proudAuth.auth.BukkitJoinFlowService;
import com.monkey.proudAuth.auth.BukkitPreLoginService;
import com.monkey.proudAuth.bootstrap.PlatformType;
import com.monkey.proudAuth.bootstrap.ProudAuthBootstrap;
import com.monkey.proudAuth.bootstrap.ProudAuthRuntime;
import com.monkey.proudAuth.bridge.BackendJoinProbeResponder;
import com.monkey.proudAuth.commands.*;
import com.monkey.proudAuth.commands.admin.ProudAuthAdminCommand;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.listeners.*;
import com.monkey.proudAuth.protection.PlayerProtection;
import com.monkey.proudAuth.update.SpigotUpdateChecker;
import com.monkey.proudAuth.update.UpdateCheckResult;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.logging.Level;

public final class ProudAuth extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 30843;
    private static final int SPIGOT_RESOURCE_ID = 134388;

    private final ProudAuthBootstrap bootstrap = new ProudAuthBootstrap();

    private PluginConfig pluginConfig;
    private LangConfig langConfig;
    private ProudAuthRuntime runtime;
    private IdentityClaimService identityClaimService;
    private PlayerProtection playerProtection;
    private PlayerPreLoginListener playerPreLoginListener;
    private BukkitTask cleanupTask;
    private BukkitTask backendJoinProbeTask;
    private ProudAuthConsoleLogger logger;
    private SpigotUpdateChecker spigotUpdateChecker;
    private volatile UpdateCheckResult latestUpdateCheckResult;

    @Override
    public void onEnable() {
        try {
            logger = new ProudAuthConsoleLogger(
                    "ProudAuth/Bukkit",
                    getLogger()::info,
                    getLogger()::warning,
                    (message, throwable) -> {
                        if (throwable == null) {
                            getLogger().severe(message);
                        } else {
                            getLogger().log(Level.SEVERE, message, throwable);
                        }
                    }
            );
            pluginConfig = new PluginConfig(this);
            langConfig = new LangConfig(this, pluginConfig);
            runtime = bootstrap.boot(PlatformType.BUKKIT, pluginConfig.settings());
            identityClaimService = new IdentityClaimService(runtime.storage(), pluginConfig.settings());
            playerProtection = new PlayerProtection(this, pluginConfig, langConfig, logger);
            spigotUpdateChecker = new SpigotUpdateChecker(task -> Bukkit.getScheduler().runTaskAsynchronously(this, task));
            latestUpdateCheckResult = UpdateCheckResult.notChecked(getPluginMeta().getVersion(), spigotResourceId());

            logger.banner(
                    "ProudAuth v" + getPluginMeta().getVersion(),
                    "Platform: Bukkit backend",
                    "Proxy mode: " + pluginConfig.settings().proxy().mode(),
                    "Language: " + langConfig.activeLanguageDescription(),
                    "Bridge: " + (pluginConfig.settings().bridge().enabled()
                            ? "enabled (" + pluginConfig.settings().bridge().mode() + ")"
                            : "disabled"),
                    "Debugger: " + pluginConfig.settings().debugger().summary()
            );

            initializeMetrics();
            registerListeners();
            registerCommands();
            startCleanupTask();
            startBackendJoinProbeTask();
            runUpdateCheck();
            logger.success("Bukkit backend enabled.");
        } catch (Exception exception) {
            if (logger != null) {
                logger.error("Impossibile avviare ProudAuth.", exception);
            } else {
                getLogger().log(Level.SEVERE, "Impossibile avviare ProudAuth.", exception);
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (backendJoinProbeTask != null) {
            backendJoinProbeTask.cancel();
        }
        if (runtime != null) {
            bootstrap.close(runtime);
        }
        if (logger != null) {
            logger.info("Bukkit backend disabled.");
        }
    }

    public void reloadPluginState() {
        pluginConfig.reload();
        langConfig.reload();
        runtime = bootstrap.reload(runtime, pluginConfig.settings());
        identityClaimService.reload(pluginConfig.settings());
        playerProtection.reload(pluginConfig, langConfig);
        HandlerList.unregisterAll(this);
        registerListeners();
        registerCommands();
        startCleanupTask();
        startBackendJoinProbeTask();
        logger.info("Reload completed. Language: "
                + langConfig.activeLanguageDescription()
                + ". Debugger: "
                + pluginConfig.settings().debugger().summary());
        logger.debugEvent(pluginConfig.settings().debugger(), DebugChannel.COMMAND_FLOW,
                "backend_reload_applied",
                "proxy_mode", pluginConfig.settings().proxy().mode(),
                "bridge_enabled", pluginConfig.settings().bridge().enabled());
        runUpdateCheck();
    }

    private void startCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        long intervalTicks = 20L * 60L * 30L;
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    runtime.sessionManager().cleanupExpired().exceptionally(exception -> 0).join();
                    runtime.storage().deleteExpiredProxyAssertions().exceptionally(exception -> 0).join();
                    runtime.storage().deleteExpiredBackendJoinProbes().exceptionally(exception -> 0).join();
                    runtime.storage().deleteExpiredPendingIdentityClaims().exceptionally(exception -> 0).join();
                },
                intervalTicks,
                intervalTicks
        );
    }

    private void startBackendJoinProbeTask() {
        if (backendJoinProbeTask != null) {
            backendJoinProbeTask.cancel();
        }

        if (pluginConfig.settings().proxy().mode() != ProudAuthSettings.ProxyMode.VELOCITY
                || !pluginConfig.settings().bridge().enabled()) {
            return;
        }

        BackendJoinProbeResponder responder = new BackendJoinProbeResponder(
                () -> runtime.storage(),
                () -> pluginConfig.settings().debugger(),
                logger,
                getServer().getName()
        );

        backendJoinProbeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                responder::poll,
                1L,
                2L
        );
    }

    private void registerListeners() {
        BukkitPreLoginService preLoginService = new BukkitPreLoginService(
                pluginConfig,
                langConfig,
                runtime.premiumVerifier(),
                runtime.bridgeService(),
                runtime.bruteForceGuard(),
                runtime.storage(),
                identityClaimService,
                logger
        );
        playerPreLoginListener = new PlayerPreLoginListener(preLoginService);

        BukkitJoinFlowService joinFlowService = new BukkitJoinFlowService(
                this,
                pluginConfig,
                langConfig,
                runtime.authService(),
                runtime.sessionManager(),
                playerProtection,
                identityClaimService,
                logger
        );

        getServer().getPluginManager().registerEvents(playerPreLoginListener, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(
                pluginConfig,
                playerPreLoginListener,
                joinFlowService,
                logger
        ), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(
                runtime.authService(),
                playerProtection,
                identityClaimService
        ), this);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(pluginConfig, playerProtection, langConfig, logger), this);
        if (pluginConfig.settings().debugger().isEnabled(DebugChannel.TELEPORT_AUDIT)) {
            getServer().getPluginManager().registerEvents(new PlayerTeleportDebugListener(pluginConfig, playerProtection, logger), this);
        }
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this, playerProtection, runtime.authService(), langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerCommandPreprocessListener(playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerActionBlockListener(playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(playerProtection), this);
        getServer().getPluginManager().registerEvents(new AdminUpdateNotifyListener(
                this,
                langConfig,
                this::notifyAdminOnJoinEnabled,
                this::latestUpdateCheckResult
        ), this);
    }

    private void registerCommands() {
        LoginCommand loginCommand = new LoginCommand(this, langConfig, runtime.authService(), playerProtection, identityClaimService);
        RegisterCommand registerCommand = new RegisterCommand(this, langConfig, runtime.authService(), identityClaimService);
        ChangePasswordCommand changePasswordCommand = new ChangePasswordCommand(this, langConfig, runtime.authService());
        LogoutCommand logoutCommand = new LogoutCommand(this, langConfig, runtime.authService(), playerProtection);
        TwoFactorCommand twoFactorCommand = new TwoFactorCommand(this, langConfig, runtime.authService(), playerProtection);
        SpCommand spCommand = new SpCommand(this, langConfig, identityClaimService, playerProtection);
        PremiumClaimCommand premiumClaimCommand = new PremiumClaimCommand(
                this,
                pluginConfig,
                langConfig,
                identityClaimService,
                runtime.premiumVerifier()
        );

        Objects.requireNonNull(getCommand("login")).setExecutor(loginCommand);
        Objects.requireNonNull(getCommand("login")).setTabCompleter(loginCommand);
        Objects.requireNonNull(getCommand("register")).setExecutor(registerCommand);
        Objects.requireNonNull(getCommand("register")).setTabCompleter(registerCommand);
        Objects.requireNonNull(getCommand("sp")).setExecutor(spCommand);
        Objects.requireNonNull(getCommand("sp")).setTabCompleter(spCommand);
        Objects.requireNonNull(getCommand("premium")).setExecutor(premiumClaimCommand);
        Objects.requireNonNull(getCommand("premium")).setTabCompleter(premiumClaimCommand);
        Objects.requireNonNull(getCommand("changepassword")).setExecutor(changePasswordCommand);
        Objects.requireNonNull(getCommand("changepassword")).setTabCompleter(changePasswordCommand);
        Objects.requireNonNull(getCommand("logout")).setExecutor(logoutCommand);
        Objects.requireNonNull(getCommand("logout")).setTabCompleter(logoutCommand);
        Objects.requireNonNull(getCommand("2fa")).setExecutor(twoFactorCommand);
        Objects.requireNonNull(getCommand("2fa")).setTabCompleter(twoFactorCommand);

        ProudAuthAdminCommand adminCommand = new ProudAuthAdminCommand(
                this,
                pluginConfig,
                langConfig,
                runtime.authService(),
                playerProtection,
                runtime.bruteForceGuard(),
                runtime.storage(),
                runtime.sessionManager(),
                identityClaimService,
                runtime.premiumVerifier(),
                this::reloadPluginState
        );
        Objects.requireNonNull(getCommand("proudauth")).setExecutor(adminCommand);
        Objects.requireNonNull(getCommand("proudauth")).setTabCompleter(adminCommand);
    }

    private void initializeMetrics() {
        int pluginId = bStatsPluginId();
        try {
            Metrics metrics = new Metrics(this, pluginId);
            metrics.addCustomChart(new SimplePie("proxy_mode", () -> pluginConfig.settings().proxy().mode().name()));
            metrics.addCustomChart(new SimplePie("bridge_enabled", () -> String.valueOf(pluginConfig.settings().bridge().enabled())));
            logger.info("bStats enabled. pluginId=" + pluginId);
        } catch (Exception exception) {
            logger.warn("bStats initialization failed: " + exception.getMessage());
        }
    }

    private void runUpdateCheck() {
        if (!updateCheckEnabled()) {
            latestUpdateCheckResult = UpdateCheckResult.notChecked(getPluginMeta().getVersion(), spigotResourceId());
            logger.info("Update checker disabled by configuration.");
            return;
        }

        int resourceId = spigotResourceId();
        String currentVersion = getPluginMeta().getVersion();
        spigotUpdateChecker.check(resourceId, currentVersion).whenComplete((result, throwable) -> {
            if (throwable != null) {
                latestUpdateCheckResult = UpdateCheckResult.failed(currentVersion, resourceId, throwable.getClass().getSimpleName());
                logger.warn("Update check failed for Spigot resource " + resourceId + ": " + throwable.getClass().getSimpleName());
                return;
            }

            latestUpdateCheckResult = result;
            if (!result.error().isBlank()) {
                logger.warn("Update check failed for Spigot resource " + resourceId + ": " + result.error());
                return;
            }

            if (result.updateAvailable()) {
                logger.warn("Update available. current="
                        + result.currentVersion()
                        + " latest="
                        + result.latestVersion()
                        + " url="
                        + result.resourceUrl());
            } else {
                logger.info("No updates found. current=" + result.currentVersion() + " latest=" + result.latestVersion());
            }
        });
    }

    private int bStatsPluginId() {
        return BSTATS_PLUGIN_ID;
    }

    private boolean updateCheckEnabled() {
        return getConfig().getBoolean("updates.check-enabled", true);
    }

    private boolean notifyAdminOnJoinEnabled() {
        return getConfig().getBoolean("updates.notify-admin-on-join", true);
    }

    private int spigotResourceId() {
        return SPIGOT_RESOURCE_ID;
    }

    private UpdateCheckResult latestUpdateCheckResult() {
        return latestUpdateCheckResult == null
                ? UpdateCheckResult.notChecked(getPluginMeta().getVersion(), spigotResourceId())
                : latestUpdateCheckResult;
    }
}
