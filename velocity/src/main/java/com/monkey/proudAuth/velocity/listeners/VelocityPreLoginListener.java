package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

public final class VelocityPreLoginListener {

    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final VelocityBackendJoinProbeService backendJoinProbeService;
    private final ProudAuthConsoleLogger logger;

    public VelocityPreLoginListener(
            Supplier<StorageProvider> storageSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityBackendJoinProbeService backendJoinProbeService,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.backendJoinProbeService = backendJoinProbeService;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> handlePreLogin(event));
    }

    private void handlePreLogin(PreLoginEvent event) {
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
            return;
        }

        VelocityBackendJoinProbeService.ProbeStatus probeStatus = backendJoinProbeService.probe(event.getUsername(), ipAddress);
        debug("Velocity pre-login backend probe player=%s ip=%s status=%s",
                event.getUsername(),
                ipAddress,
                probeStatus);
        if (probeStatus == VelocityBackendJoinProbeService.ProbeStatus.TIMEOUT
                || probeStatus == VelocityBackendJoinProbeService.ProbeStatus.ERROR) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-backend-unavailable")));
        }
    }

    private void debug(String template, Object... args) {
        logger.debug(debuggerSupplier.get(), DebugChannel.IP_BAN_FLOW, template, args);
    }
}
