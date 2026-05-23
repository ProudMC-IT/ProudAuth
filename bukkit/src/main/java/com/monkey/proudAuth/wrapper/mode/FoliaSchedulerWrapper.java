package com.monkey.proudAuth.wrapper.mode;

import com.monkey.proudAuth.wrapper.ScheduledTaskHandle;
import com.monkey.proudAuth.wrapper.SchedulerWrapper;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

public final class FoliaSchedulerWrapper implements SchedulerWrapper {

    private final JavaPlugin plugin;

    public FoliaSchedulerWrapper(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public ScheduledTaskHandle runSync(Runnable task) {
        return plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduledTask -> task.run())::cancel;
    }

    @Override
    public ScheduledTaskHandle runSyncLater(Runnable task, long delayTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runDelayed(plugin, scheduledTask -> task.run(), Math.max(0L, delayTicks))::cancel;
    }

    @Override
    public ScheduledTaskHandle runAsync(Runnable task) {
        return plugin.getServer().getAsyncScheduler().runNow(plugin, scheduledTask -> task.run())::cancel;
    }

    @Override
    public ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin,
                scheduledTask -> task.run(),
                ticksToDelayMillis(delayTicks),
                ticksToPeriodMillis(periodTicks),
                TimeUnit.MILLISECONDS
        )::cancel;
    }

    @Override
    public ScheduledTaskHandle runPlayer(Player player, Runnable task) {
        return player.getScheduler().run(plugin, scheduledTask -> task.run(), () -> {
        })::cancel;
    }

    @Override
    public ScheduledTaskHandle runPlayerLater(Player player, Runnable task, long delayTicks) {
        return player.getScheduler().runDelayed(plugin, scheduledTask -> task.run(), () -> {
        }, Math.max(0L, delayTicks))::cancel;
    }

    private long ticksToDelayMillis(long ticks) {
        return Math.max(0L, ticks) * 50L;
    }

    private long ticksToPeriodMillis(long ticks) {
        return Math.max(1L, ticks) * 50L;
    }
}
