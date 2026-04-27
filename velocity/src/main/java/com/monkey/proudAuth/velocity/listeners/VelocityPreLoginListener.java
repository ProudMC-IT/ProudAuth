package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.session.VelocityWhitelistEnforcementStore;
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
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityPreLoginListener(
            Supplier<StorageProvider> storageSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityBackendJoinProbeService backendJoinProbeService,
            VelocityNetworkGuardService networkGuardService,
            VelocityWhitelistEnforcementStore whitelistEnforcementStore,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.backendJoinProbeService = backendJoinProbeService;
        this.networkGuardService = networkGuardService;
        this.whitelistEnforcementStore = whitelistEnforcementStore;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> handlePreLogin(event));
    }

    private void handlePreLogin(PreLoginEvent event) {
        String ipAddress = resolveIp(event);
        debugEvent("prelogin_start",
                "player", event.getUsername(),
                "ip", ipAddress);

        VelocityLang lang = langSupplier.get();
        StorageProvider storage = storageSupplier.get();

        var activeBan = storage.findActiveBan(ipAddress).join();
        debugEvent("prelogin_ip_ban_check",
                "player", event.getUsername(),
                "ip", ipAddress,
                "banned", activeBan.isPresent());
        if (activeBan.isPresent()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-ip-banned")));
            return;
        }

        var trustedIp = storage.findTrustedIp(event.getUsername()).join();
        if (trustedIp.isPresent() && !trustedIp.get().equalsIgnoreCase(ipAddress)) {
            debugEvent("prelogin_trusted_ip_mismatch",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "trusted_ip", trustedIp.get());
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-trusted-ip-mismatch")));
            return;
        }

        boolean inWhitelist = storage.isInPremiumIpWhitelist(event.getUsername()).join();
        if (inWhitelist) {
            boolean ipAllowed = storage.isPremiumIpWhitelisted(event.getUsername(), ipAddress).join();
            debugEvent("prelogin_premium_whitelist_check",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "ip_allowed", ipAllowed);
            if (!ipAllowed) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        lang.message("kick-whitelist-ip-required")));
                return;
            }
            whitelistEnforcementStore.remember(event.getUsername(), ipAddress);
        }

        VelocityNetworkGuardService.GuardDecision guardDecision = networkGuardService.evaluate(event.getUsername(), ipAddress);
        debugEvent("prelogin_network_guard",
                "player", event.getUsername(),
                "ip", ipAddress,
                "allowed", guardDecision.allowed(),
                "reason", guardDecision.reason());
        if (!guardDecision.allowed()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-suspicious-network")));
            return;
        }

        VelocityBackendJoinProbeService.ProbeStatus probeStatus = backendJoinProbeService.probe(event.getUsername(), ipAddress);
        debugEvent("prelogin_backend_probe",
                "player", event.getUsername(),
                "ip", ipAddress,
                "status", probeStatus);
        if (probeStatus == VelocityBackendJoinProbeService.ProbeStatus.TIMEOUT
                || probeStatus == VelocityBackendJoinProbeService.ProbeStatus.ERROR) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-backend-unavailable")));
        }
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.IP_BAN_FLOW, eventName, keyValues);
    }

    private String resolveIp(PreLoginEvent event) {
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
