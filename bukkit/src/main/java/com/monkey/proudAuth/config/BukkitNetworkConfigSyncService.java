package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfigCodec;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.NetworkConfigSnapshotRecord;
import com.monkey.proudAuth.common.storage.StorageProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Optional;
import java.util.function.Supplier;

public final class BukkitNetworkConfigSyncService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final Supplier<StorageProvider> storageSupplier;
    private final ProudAuthConsoleLogger logger;
    private final ProudAuthNetworkConfigCodec codec = new ProudAuthNetworkConfigCodec();
    private volatile long activeVersion = -1L;
    private volatile String activeHash = "";
    private volatile BukkitTask task;

    public BukkitNetworkConfigSyncService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            Supplier<StorageProvider> storageSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.storageSupplier = storageSupplier;
        this.logger = logger;
    }

    public ProudAuthNetworkConfig loadInitialConfig() {
        NetworkConfigSnapshotRecord snapshot = storageSupplier.get()
                .fetchActiveNetworkConfig()
                .join()
                .orElseThrow(() -> new IllegalStateException("Nessuna network config centrale trovata nel database."));
        return applySnapshot(snapshot);
    }

    public boolean refreshNow() {
        Optional<NetworkConfigSnapshotRecord> snapshot = storageSupplier.get().fetchActiveNetworkConfig().join();
        if (snapshot.isEmpty()) {
            throw new IllegalStateException("Nessuna network config centrale trovata nel database.");
        }
        return applySnapshotIfChanged(snapshot.get());
    }

    public void start(Runnable reloadAction) {
        stop();
        long intervalTicks = Math.max(20L, pluginConfig.configSyncPollIntervalSeconds() * 20L);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> poll(reloadAction), intervalTicks, intervalTicks);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void poll(Runnable reloadAction) {
        try {
            Optional<NetworkConfigSnapshotRecord> state = storageSupplier.get().fetchActiveNetworkConfigState().join();
            if (state.isEmpty()) {
                return;
            }
            NetworkConfigSnapshotRecord snapshotState = state.get();
            if (snapshotState.version() == activeVersion && snapshotState.configHash().equals(activeHash)) {
                return;
            }

            Optional<NetworkConfigSnapshotRecord> snapshot = storageSupplier.get().fetchActiveNetworkConfig().join();
            if (snapshot.isEmpty()) {
                return;
            }
            if (!applySnapshotIfChanged(snapshot.get())) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, reloadAction);
        } catch (Exception exception) {
            logger.warn("Config sync poll failed: " + exception.getMessage());
        }
    }

    private boolean applySnapshotIfChanged(NetworkConfigSnapshotRecord snapshot) {
        if (snapshot.version() == activeVersion && snapshot.configHash().equals(activeHash)) {
            return false;
        }
        applySnapshot(snapshot);
        return true;
    }

    private ProudAuthNetworkConfig applySnapshot(NetworkConfigSnapshotRecord snapshot) {
        ProudAuthNetworkConfig networkConfig = codec.parse(snapshot.document());
        pluginConfig.applyNetworkConfig(networkConfig);
        activeVersion = snapshot.version();
        activeHash = snapshot.configHash();
        logger.info("Applied shared network config version " + snapshot.version() + " from " + snapshot.sourceNode() + ".");
        return networkConfig;
    }
}
