package com.monkey.proudAuth.common.logging;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class ProudAuthConsoleLogger {

    private static final String SEPARATOR = "==================================================";

    private final String prefix;
    private final Consumer<String> infoConsumer;
    private final Consumer<String> warnConsumer;
    private final BiConsumer<String, Throwable> errorConsumer;

    public ProudAuthConsoleLogger(
            String name,
            Consumer<String> infoConsumer,
            Consumer<String> warnConsumer,
            BiConsumer<String, Throwable> errorConsumer
    ) {
        this.prefix = "[" + Objects.requireNonNull(name, "name") + "]";
        this.infoConsumer = Objects.requireNonNull(infoConsumer, "infoConsumer");
        this.warnConsumer = Objects.requireNonNull(warnConsumer, "warnConsumer");
        this.errorConsumer = Objects.requireNonNull(errorConsumer, "errorConsumer");
    }

    public void banner(String... lines) {
        infoConsumer.accept(SEPARATOR);
        for (String line : lines) {
            info(line);
        }
        infoConsumer.accept(SEPARATOR);
    }

    public void info(String message) {
        infoConsumer.accept(prefix + " " + message);
    }

    public void success(String message) {
        info(message);
    }

    public void warn(String message) {
        warnConsumer.accept(prefix + " " + message);
    }

    public void error(String message) {
        errorConsumer.accept(prefix + " " + message, null);
    }

    public void error(String message, Throwable throwable) {
        errorConsumer.accept(prefix + " " + message, throwable);
    }

    public boolean isEnabled(ProudAuthSettings.Debugger debugger, DebugChannel channel) {
        return debugger != null && debugger.isEnabled(channel);
    }

    public void debug(ProudAuthSettings.Debugger debugger, DebugChannel channel, String template, Object... args) {
        if (!isEnabled(debugger, channel)) {
            return;
        }
        info("[" + channel.configKey() + "] " + template.formatted(args));
    }
}
