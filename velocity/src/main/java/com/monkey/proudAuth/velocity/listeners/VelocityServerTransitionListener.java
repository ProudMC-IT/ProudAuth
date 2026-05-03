package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.config.VelocityPluginSettings;
import com.monkey.proudAuth.velocity.session.VelocityPremiumClaimFailureStore;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.function.Supplier;

public final class VelocityServerTransitionListener {

    private final ProxyServer proxyServer;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final VelocityPremiumClaimFailureStore premiumClaimFailureStore;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<VelocityPluginSettings.Routing> routingSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityServerTransitionListener(
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            VelocityPremiumClaimFailureStore premiumClaimFailureStore,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<VelocityPluginSettings.Routing> routingSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.proxyServer = proxyServer;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.premiumClaimFailureStore = premiumClaimFailureStore;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.routingSupplier = routingSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onServerPreConnect(ServerPreConnectEvent event) {
        String ipAddress = ipAddress(event.getPlayer());
        String previousServer = event.getPreviousServer() == null
                ? ""
                : event.getPreviousServer().getServerInfo().getName();
        String selectedTargetServer = event.getResult().getServer()
                .orElse(event.getOriginalServer())
                .getServerInfo()
                .getName();
        boolean initialConnection = event.getPreviousServer() == null;

        var resolvedPlayer = resolvedPlayerStore.find(event.getPlayer().getUsername());
        debugEvent("server_switch_detected",
                "player", event.getPlayer().getUsername(),
                "previous", previousServer,
                "target", selectedTargetServer,
                "ip", ipAddress,
                "initial", initialConnection,
                "cache_hit", resolvedPlayer.isPresent(),
                "bridge_operational", bridgeServiceSupplier.get().isOperational());

        if (resolvedPlayer.isEmpty()) {
            return;
        }

        VelocityResolvedPlayerStore.ResolvedPlayer profile = resolvedPlayer.get();
        VelocityPluginSettings.Routing routing = routingSupplier.get();
        String authEntryServer = resolveAuthEntryServer(initialConnection, previousServer, selectedTargetServer, profile, routing);
        String targetServer = resolveEffectiveTargetServer(initialConnection, selectedTargetServer, profile, authEntryServer);
        RegisteredServer targetRegisteredServer = findServer(targetServer).orElse(null);
        if (targetRegisteredServer != null && !targetServer.equalsIgnoreCase(selectedTargetServer)) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetRegisteredServer));
        }
        resolvedPlayerStore.rememberAuthEntryServer(event.getPlayer().getUsername(), authEntryServer);

        BridgeJoinMode joinMode = profile.networkAuthenticated()
                ? BridgeJoinMode.NETWORK_TRANSFER
                : BridgeJoinMode.AUTH_ENTRY;
        bridgeServiceSupplier.get()
                .publish(
                        event.getPlayer().getUsername(),
                        profile.accountName(),
                        profile.accountUuid(),
                        profile.accountType(),
                        ipAddress,
                        joinMode,
                        targetServer,
                        authEntryServer,
                        routing.hasAuthEntryServer(),
                        routing.hasPostAuthServer() ? routing.postAuthServer() : "",
                        profile.networkAuthenticated()
                )
                .exceptionally(exception -> {
                    debugEvent("server_switch_publish_error",
                            "player", event.getPlayer().getUsername(),
                            "target", targetServer,
                            "error", exception.getMessage());
                    return null;
                })
                .join();

        debugEvent("server_switch_publish_done",
                "player", event.getPlayer().getUsername(),
                "target", targetServer,
                "join_mode", joinMode,
                "network_authenticated", profile.networkAuthenticated(),
                "resolved_uuid", profile.accountUuid(),
                "account_type", profile.accountType());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String username = event.getPlayer().getUsername();
        String ipAddress = ipAddress(event.getPlayer());
        var resolvedPlayer = resolvedPlayerStore.find(username);
        if (resolvedPlayer.isPresent()
                && resolvedPlayer.get().accountType() == AccountType.CRACKED
                && premiumClaimFailureStore.consume(username, ipAddress)) {
            event.getPlayer().sendMessage(langSupplier.get().message("claim-premium-failed-choose-again"));
            debugEvent("premium_claim_failed_notice_sent",
                    "player", username,
                    "server", event.getServer().getServerInfo().getName(),
                    "ip", ipAddress);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resolvedPlayerStore.forget(event.getPlayer().getUsername());
        debugEvent("server_switch_cache_cleared",
                "player", event.getPlayer().getUsername());
    }

    private String resolveAuthEntryServer(
            boolean initialConnection,
            String previousServer,
            String selectedTargetServer,
            VelocityResolvedPlayerStore.ResolvedPlayer profile,
            VelocityPluginSettings.Routing routing
    ) {
        if (routing.hasAuthEntryServer()) {
            return routing.authEntryServer();
        }
        if (profile.authEntryServer() != null && !profile.authEntryServer().isBlank()) {
            return profile.authEntryServer();
        }
        return initialConnection ? selectedTargetServer : previousServer;
    }

    private String resolveEffectiveTargetServer(
            boolean initialConnection,
            String selectedTargetServer,
            VelocityResolvedPlayerStore.ResolvedPlayer profile,
            String authEntryServer
    ) {
        if (profile.networkAuthenticated()) {
            return selectedTargetServer;
        }
        if (initialConnection) {
            return authEntryServer;
        }
        if (authEntryServer != null && !authEntryServer.isBlank()) {
            return authEntryServer;
        }
        return selectedTargetServer;
    }

    private Optional<RegisteredServer> findServer(String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return Optional.empty();
        }
        return proxyServer.getServer(serverName);
    }

    private String ipAddress(com.velocitypowered.api.proxy.Player player) {
        if (player.getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }
}
