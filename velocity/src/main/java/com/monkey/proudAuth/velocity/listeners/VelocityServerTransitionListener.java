package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

public final class VelocityServerTransitionListener {

    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityServerTransitionListener(
            VelocityResolvedPlayerStore resolvedPlayerStore,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (event.getPreviousServer() == null) {
            return;
        }

        String ipAddress = ipAddress(event.getPlayer());
        String previousServer = event.getPreviousServer().getServerInfo().getName();
        String targetServer = event.getResult().getServer()
                .orElse(event.getOriginalServer())
                .getServerInfo()
                .getName();

        var resolvedPlayer = resolvedPlayerStore.find(event.getPlayer().getUsername());
        debug("Velocity server switch bridge player=%s previous=%s target=%s ip=%s cacheHit=%s bridgeOperational=%s",
                event.getPlayer().getUsername(),
                previousServer,
                targetServer,
                ipAddress,
                resolvedPlayer.isPresent(),
                bridgeServiceSupplier.get().isOperational());

        if (resolvedPlayer.isEmpty()) {
            return;
        }

        VelocityResolvedPlayerStore.ResolvedPlayer profile = resolvedPlayer.get();
        bridgeServiceSupplier.get()
                .publish(
                        event.getPlayer().getUsername(),
                        profile.resolvedName(),
                        profile.resolvedUuid(),
                        profile.accountType(),
                        ipAddress
                )
                .exceptionally(exception -> {
                    debug("Velocity server switch bridge error player=%s target=%s error=%s",
                            event.getPlayer().getUsername(),
                            targetServer,
                            exception.getMessage());
                    return null;
                })
                .join();

        debug("Velocity server switch bridge published player=%s target=%s resolvedUuid=%s accountType=%s",
                event.getPlayer().getUsername(),
                targetServer,
                profile.resolvedUuid(),
                profile.accountType());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resolvedPlayerStore.forget(event.getPlayer().getUsername());
        debug("Velocity server switch cache cleared player=%s", event.getPlayer().getUsername());
    }

    private String ipAddress(com.velocitypowered.api.proxy.Player player) {
        if (player.getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private void debug(String template, Object... args) {
        logger.debug(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, template, args);
    }
}
