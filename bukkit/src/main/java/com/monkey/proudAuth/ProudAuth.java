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
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.listeners.*;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;
import java.util.logging.Level;

public final class ProudAuth extends JavaPlugin {

    private final ProudAuthBootstrap bootstrap = new ProudAuthBootstrap();

    private PluginConfig pluginConfig;
    private LangConfig langConfig;
    private ProudAuthRuntime runtime;
    private PlayerProtection playerProtection;
    private PlayerPreLoginListener playerPreLoginListener;
    private BukkitTask cleanupTask;
    private BukkitTask backendJoinProbeTask;
    private ProudAuthConsoleLogger logger;

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
            playerProtection = new PlayerProtection(this, pluginConfig, langConfig, logger);

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

            registerListeners();
            registerCommands();
            startCleanupTask();
            startBackendJoinProbeTask();
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
        logger.debug(pluginConfig.settings().debugger(), DebugChannel.COMMAND_FLOW,
                "Backend reload applied with proxyMode=%s bridge=%s",
                pluginConfig.settings().proxy().mode(),
                pluginConfig.settings().bridge().enabled());
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
                logger
        );

        getServer().getPluginManager().registerEvents(playerPreLoginListener, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(
                pluginConfig,
                playerPreLoginListener,
                joinFlowService,
                logger
        ), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(runtime.authService(), playerProtection), this);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(pluginConfig, playerProtection, langConfig, logger), this);
        if (pluginConfig.settings().debugger().isEnabled(DebugChannel.TELEPORT_AUDIT)) {
            getServer().getPluginManager().registerEvents(new PlayerTeleportDebugListener(pluginConfig, playerProtection, logger), this);
        }
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this, playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerCommandPreprocessListener(playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerInteractListener(playerProtection, langConfig), this);
        getServer().getPluginManager().registerEvents(new PlayerDeathListener(playerProtection), this);
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("login")).setExecutor(new LoginCommand(this, langConfig, runtime.authService(), playerProtection));
        Objects.requireNonNull(getCommand("register")).setExecutor(new RegisterCommand(this, langConfig, runtime.authService()));
        Objects.requireNonNull(getCommand("changepassword")).setExecutor(new ChangePasswordCommand(this, langConfig, runtime.authService()));
        Objects.requireNonNull(getCommand("logout")).setExecutor(new LogoutCommand(this, langConfig, runtime.authService(), playerProtection));
        Objects.requireNonNull(getCommand("2fa")).setExecutor(new TwoFactorCommand(this, langConfig, runtime.authService(), playerProtection));

        ProudAuthAdminCommand adminCommand = new ProudAuthAdminCommand(
                this,
                pluginConfig,
                langConfig,
                runtime.authService(),
                playerProtection,
                runtime.bruteForceGuard(),
                runtime.storage(),
                this::reloadPluginState
        );
        Objects.requireNonNull(getCommand("proudauth")).setExecutor(adminCommand);
        Objects.requireNonNull(getCommand("proudauth")).setTabCompleter(adminCommand);
    }
}
