package com.monkey.proudAuth;

import com.monkey.proudAuth.bootstrap.PlatformType;
import com.monkey.proudAuth.bootstrap.ProudAuthBootstrap;
import com.monkey.proudAuth.bootstrap.ProudAuthRuntime;
import com.monkey.proudAuth.commands.ChangePasswordCommand;
import com.monkey.proudAuth.commands.LoginCommand;
import com.monkey.proudAuth.commands.LogoutCommand;
import com.monkey.proudAuth.commands.RegisterCommand;
import com.monkey.proudAuth.commands.TwoFactorCommand;
import com.monkey.proudAuth.commands.admin.ProudAuthAdminCommand;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.listeners.PlayerChatListener;
import com.monkey.proudAuth.listeners.PlayerCommandPreprocessListener;
import com.monkey.proudAuth.listeners.PlayerDeathListener;
import com.monkey.proudAuth.listeners.PlayerInteractListener;
import com.monkey.proudAuth.listeners.PlayerJoinListener;
import com.monkey.proudAuth.listeners.PlayerMoveListener;
import com.monkey.proudAuth.listeners.PlayerPreLoginListener;
import com.monkey.proudAuth.listeners.PlayerQuitListener;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Objects;

public final class ProudAuth extends JavaPlugin {

    private final ProudAuthBootstrap bootstrap = new ProudAuthBootstrap();

    private PluginConfig pluginConfig;
    private LangConfig langConfig;
    private ProudAuthRuntime runtime;
    private PlayerProtection playerProtection;
    private PlayerPreLoginListener playerPreLoginListener;
    private BukkitTask cleanupTask;

    @Override
    public void onEnable() {
        try {
            pluginConfig = new PluginConfig(this);
            langConfig = new LangConfig(this);
            runtime = bootstrap.boot(PlatformType.BUKKIT, pluginConfig.settings());
            playerProtection = new PlayerProtection(this, pluginConfig, langConfig);

            registerListeners();
            registerCommands();
            startCleanupTask();
        } catch (Exception exception) {
            getLogger().severe("Impossibile avviare ProudAuth: " + exception.getMessage());
            exception.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (runtime != null) {
            bootstrap.close(runtime);
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
    }

    private void startCleanupTask() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }

        long intervalTicks = 20L * 60L * 30L;
        cleanupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> runtime.sessionManager().cleanupExpired().exceptionally(exception -> 0),
                intervalTicks,
                intervalTicks
        );
    }

    private void registerListeners() {
        playerPreLoginListener = new PlayerPreLoginListener(
                this,
                pluginConfig,
                langConfig,
                runtime.premiumVerifier(),
                runtime.bridgeService(),
                runtime.bruteForceGuard()
        );

        getServer().getPluginManager().registerEvents(playerPreLoginListener, this);
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(
                this,
                pluginConfig,
                langConfig,
                runtime.authService(),
                runtime.sessionManager(),
                playerProtection,
                playerPreLoginListener
        ), this);
        getServer().getPluginManager().registerEvents(new PlayerQuitListener(runtime.authService(), playerProtection), this);
        getServer().getPluginManager().registerEvents(new PlayerMoveListener(this, pluginConfig, playerProtection, langConfig), this);
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
