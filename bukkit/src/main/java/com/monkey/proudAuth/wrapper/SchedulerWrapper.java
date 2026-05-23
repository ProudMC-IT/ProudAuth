package com.monkey.proudAuth.wrapper;

import org.bukkit.entity.Player;

public interface SchedulerWrapper {

    ScheduledTaskHandle runSync(Runnable task);

    ScheduledTaskHandle runSyncLater(Runnable task, long delayTicks);

    ScheduledTaskHandle runAsync(Runnable task);

    ScheduledTaskHandle runAsyncRepeating(Runnable task, long delayTicks, long periodTicks);

    ScheduledTaskHandle runPlayer(Player player, Runnable task);

    ScheduledTaskHandle runPlayerLater(Player player, Runnable task, long delayTicks);
}
