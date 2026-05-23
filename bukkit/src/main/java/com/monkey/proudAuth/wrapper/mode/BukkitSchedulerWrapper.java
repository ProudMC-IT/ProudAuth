package com.monkey.proudAuth.wrapper.mode;

import com.monkey.proudAuth.wrapper.ScheduledTaskHandle;
import com.monkey.proudAuth.wrapper.SchedulerWrapper;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitSchedulerWrapper implements SchedulerWrapper {

    private final JavaPlugin plugin;

    public BukkitSchedulerWrapper(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ScheduledTaskHandle runSync(Runnable task) {
        return plugin.getServer().getScheduler().runTask(plugin, task)::cancel;
    }

    @Override
    public ScheduledTaskHandle runSyncLater(Runnable task, long delayTicks) {
        return plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks)::cancel;
    }

    @Override
    public ScheduledTaskHandle runAsync(Runnable task) {
        return plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task)::cancel;
    }

    @Override
    public ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        return plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks)::cancel;
    }

    @Override
    public ScheduledTaskHandle runPlayer(Player player, Runnable task) {
        return runSync(task);
    }

    @Override
    public ScheduledTaskHandle runPlayerLater(Player player, Runnable task, long delayTicks) {
        return runSyncLater(task, delayTicks);
    }
}
