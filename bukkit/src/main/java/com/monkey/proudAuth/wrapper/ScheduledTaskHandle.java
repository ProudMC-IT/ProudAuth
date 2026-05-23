package com.monkey.proudAuth.wrapper;

@FunctionalInterface
public interface ScheduledTaskHandle {

    ScheduledTaskHandle NO_OP = () -> {
    };

    void cancel();
}
