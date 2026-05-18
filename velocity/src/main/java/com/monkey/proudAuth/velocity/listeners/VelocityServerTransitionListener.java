package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.velocity.config.VelocityLang;
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
    private final Supplier<ProudAuthNetworkConfig.Routing> routingSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityServerTransitionListener(
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            VelocityPremiumClaimFailureStore premiumClaimFailureStore,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<ProudAuthNetworkConfig.Routing> routingSupplier,
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
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        boolean proxyAuthenticatedInitialJoin = shouldDirectAuthenticatedJoin(initialConnection, profile, routing);
        if (proxyAuthenticatedInitialJoin) {
            resolvedPlayerStore.markNetworkAuthenticated(event.getPlayer().getUsername(), true);
            if (routing.hasPostAuthServer()) {
                resolvedPlayerStore.rememberPendingNotice(event.getPlayer().getUsername(), "premium-auto-login", routing.postAuthServer());
            }
        }
        boolean effectiveNetworkAuthenticated = profile.networkAuthenticated() || proxyAuthenticatedInitialJoin;
        String authEntryServer = resolveAuthEntryServer(initialConnection, previousServer, selectedTargetServer, profile, routing);
        String targetServer = resolveEffectiveTargetServer(
                initialConnection,
                selectedTargetServer,
                authEntryServer,
                routing,
                effectiveNetworkAuthenticated,
                proxyAuthenticatedInitialJoin
        );
        RegisteredServer targetRegisteredServer = findServer(targetServer).orElse(null);
        if (targetRegisteredServer != null && !targetServer.equalsIgnoreCase(selectedTargetServer)) {
            event.setResult(ServerPreConnectEvent.ServerResult.allowed(targetRegisteredServer));
        }
        resolvedPlayerStore.rememberAuthEntryServer(event.getPlayer().getUsername(), authEntryServer);

        BridgeJoinMode joinMode = effectiveNetworkAuthenticated
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
                        effectiveNetworkAuthenticated,
                        profile.legacyClient()
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
                "network_authenticated", effectiveNetworkAuthenticated,
                "proxy_authenticated_initial_bypass", proxyAuthenticatedInitialJoin,
                "resolved_uuid", profile.accountUuid(),
                "account_type", profile.accountType());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String username = event.getPlayer().getUsername();
        String ipAddress = ipAddress(event.getPlayer());
        String currentServerName = event.getServer().getServerInfo().getName();
        var resolvedPlayer = resolvedPlayerStore.find(username);
        resolvedPlayerStore.consumePendingNotice(username, currentServerName).ifPresent(messageKey -> {
            event.getPlayer().sendMessage(langSupplier.get().message(messageKey));
            debugEvent("post_auth_notice_sent",
                    "player", username,
                    "server", currentServerName,
                    "message_key", messageKey);
        });
        if (resolvedPlayer.isPresent()
                && resolvedPlayer.get().accountType() == AccountType.CRACKED
                && premiumClaimFailureStore.consume(username, ipAddress)) {
            event.getPlayer().sendMessage(langSupplier.get().message("claim-premium-failed-choose-again"));
            debugEvent("premium_claim_failed_notice_sent",
                    "player", username,
                    "server", currentServerName,
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
            ProudAuthNetworkConfig.Routing routing
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
            String authEntryServer,
            ProudAuthNetworkConfig.Routing routing,
            boolean networkAuthenticated,
            boolean proxyAuthenticatedInitialJoin
    ) {
        if (networkAuthenticated) {
            if (initialConnection && proxyAuthenticatedInitialJoin && routing.hasPostAuthServer()) {
                return routing.postAuthServer();
            }
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

    private boolean shouldDirectAuthenticatedJoin(
            boolean initialConnection,
            VelocityResolvedPlayerStore.ResolvedPlayer profile,
            ProudAuthNetworkConfig.Routing routing
    ) {
        return initialConnection
                && !profile.networkAuthenticated()
                && profile.accountType() == AccountType.PREMIUM
                && profile.premiumVerified()
                && routing.shouldDirectAuthenticatedJoinsToPostAuth();
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
