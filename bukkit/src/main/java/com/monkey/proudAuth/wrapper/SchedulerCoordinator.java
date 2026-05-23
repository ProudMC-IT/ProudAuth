package com.monkey.proudAuth.wrapper;

import com.monkey.proudAuth.wrapper.mode.BukkitSchedulerWrapper;
import com.monkey.proudAuth.wrapper.mode.FoliaSchedulerWrapper;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class SchedulerCoordinator {

    private final SchedulerWrapper delegate;
    private final boolean foliaMode;

    public SchedulerCoordinator(JavaPlugin plugin) {
        foliaMode = isFoliaRuntime();
        delegate = foliaMode
                ? new FoliaSchedulerWrapper(plugin)
                : new BukkitSchedulerWrapper(plugin);
    }

    public boolean isFoliaMode() {
        return foliaMode;
    }

    public ScheduledTaskHandle runSync(Runnable task) {
        return delegate.runSync(task);
    }

    public ScheduledTaskHandle runSyncLater(Runnable task, long delayTicks) {
        return delegate.runSyncLater(task, delayTicks);
    }

    public ScheduledTaskHandle runAsync(Runnable task) {
        return delegate.runAsync(task);
    }

    public ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        return delegate.runAsyncRepeating(task, delayTicks, periodTicks);
    }

    public ScheduledTaskHandle runPlayer(Player player, Runnable task) {
        return delegate.runPlayer(player, task);
    }

    public ScheduledTaskHandle runPlayerLater(Player player, Runnable task, long delayTicks) {
        return delegate.runPlayerLater(player, task, delayTicks);
    }

    private boolean isFoliaRuntime() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer", false, Bukkit.getServer().getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException ignored) {
            String serverName = Bukkit.getName();
            return serverName != null && serverName.equalsIgnoreCase("folia");
        }
    }
}
