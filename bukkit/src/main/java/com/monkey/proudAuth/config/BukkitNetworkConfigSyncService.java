package com.monkey.proudAuth.config;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfigCodec;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.NetworkConfigSnapshotRecord;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.wrapper.ScheduledTaskHandle;
import com.monkey.proudAuth.wrapper.SchedulerCoordinator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.function.Supplier;

public final class BukkitNetworkConfigSyncService {

    private final JavaPlugin plugin;
    private final SchedulerCoordinator schedulerCoordinator;
    private final PluginConfig pluginConfig;
    private final Supplier<StorageProvider> storageSupplier;
    private final ProudAuthConsoleLogger logger;
    private final ProudAuthNetworkConfigCodec codec = new ProudAuthNetworkConfigCodec();
    private volatile long activeVersion = -1L;
    private volatile String activeHash = "";
    private volatile ScheduledTaskHandle task;

    public BukkitNetworkConfigSyncService(
            JavaPlugin plugin,
            SchedulerCoordinator schedulerCoordinator,
            PluginConfig pluginConfig,
            Supplier<StorageProvider> storageSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.plugin = plugin;
        this.schedulerCoordinator = schedulerCoordinator;
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
        task = schedulerCoordinator.runAsyncRepeating(() -> poll(reloadAction), intervalTicks, intervalTicks);
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
            schedulerCoordinator.runSync(reloadAction);
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
