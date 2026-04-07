package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

public final class VelocityPreLoginListener {

    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<Boolean> debugEnabledSupplier;
    private final Logger logger;

    public VelocityPreLoginListener(
            Supplier<StorageProvider> storageSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<Boolean> debugEnabledSupplier,
            Logger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.langSupplier = langSupplier;
        this.debugEnabledSupplier = debugEnabledSupplier;
        this.logger = logger;
    }

    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String ipAddress = "unknown";
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            ipAddress = socketAddress.getAddress().getHostAddress();
        }
        debug("Velocity pre-login player=%s ip=%s", event.getUsername(), ipAddress);

        VelocityLang lang = langSupplier.get();
        var activeBan = storageSupplier.get().findActiveBan(ipAddress).join();
        debug("Velocity pre-login ban result player=%s ip=%s banned=%s", event.getUsername(), ipAddress, activeBan.isPresent());
        if (activeBan.isPresent()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-ip-banned")));
        }
    }

    private void debug(String template, Object... args) {
        if (!debugEnabledSupplier.get()) {
            return;
        }
        logger.info("[DEBUG] " + template.formatted(args));
    }
}
