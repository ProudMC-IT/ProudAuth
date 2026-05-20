package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorState;
import com.monkey.proudAuth.common.network.ProudAuthNetworkChannel;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorAuthStateStore;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorService;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public final class VelocityAuthSyncListener {

    private static final MinecraftChannelIdentifier CHANNEL_IDENTIFIER =
            MinecraftChannelIdentifier.from(ProudAuthNetworkChannel.CHANNEL_ID);

    private final Object pluginOwner;
    private final ProxyServer proxyServer;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final Supplier<ProudAuthNetworkConfig.Routing> routingSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;
    private final VelocityMonitorAuthStateStore monitorAuthStateStore;
    private final Supplier<VelocityMonitorService> monitorServiceSupplier;

    public VelocityAuthSyncListener(
            Object pluginOwner,
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            Supplier<ProudAuthNetworkConfig.Routing> routingSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger,
            VelocityMonitorAuthStateStore monitorAuthStateStore,
            Supplier<VelocityMonitorService> monitorServiceSupplier
    ) {
        this.pluginOwner = pluginOwner;
        this.proxyServer = proxyServer;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.routingSupplier = routingSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
        this.monitorAuthStateStore = monitorAuthStateStore;
        this.monitorServiceSupplier = monitorServiceSupplier;
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!CHANNEL_IDENTIFIER.equals(event.getIdentifier())) {
            return;
        }

        if (!(event.getSource() instanceof ServerConnection serverConnection)) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String type = input.readUTF();
            String username = input.readUTF();
            String backendServerId = input.readUTF();
            String payload = readOptionalPayload(input);
            String sourceServer = serverConnection.getServerInfo().getName();

            if (!sourceServer.equalsIgnoreCase(backendServerId)) {
                debugEvent("auth_sync_server_mismatch",
                        "player", username,
                        "source_server", sourceServer,
                        "backend_server", backendServerId,
                        "type", type);
                return;
            }

            Optional<Player> player = proxyServer.getPlayer(username);
            if (player.isEmpty()) {
                debugEvent("auth_sync_player_offline",
                        "player", username,
                        "server", sourceServer,
                        "type", type);
                return;
            }

            if (ProudAuthNetworkChannel.AUTH_STATE_UPDATE.equals(type)) {
                handleAuthStateUpdate(player.get(), sourceServer, payload);
                return;
            }

            if (ProudAuthNetworkChannel.AUTH_COMPLETED.equals(type)) {
                handleAuthCompleted(player.get(), sourceServer);
                return;
            }

            if (ProudAuthNetworkChannel.AUTH_INVALIDATED.equals(type)) {
                handleAuthInvalidated(player.get(), sourceServer);
                return;
            }

            if (ProudAuthNetworkChannel.AUTH_NOTICE.equals(type)) {
                handleAuthNotice(player.get(), sourceServer, payload);
            }
        } catch (IOException exception) {
            debugEvent("auth_sync_decode_error",
                    "error", exception.getMessage());
        }
    }

    private String readOptionalPayload(DataInputStream input) {
        try {
            return input.readUTF();
        } catch (IOException ignored) {
            return "";
        }
    }

    private void handleAuthStateUpdate(Player player, String sourceServer, String payload) {
        ProudAuthMonitorState state = ProudAuthMonitorState.from(payload);
        monitorAuthStateStore.update(player.getUsername(), state);

        debugEvent("auth_sync_monitor_state_update",
                "player", player.getUsername(),
                "server", sourceServer,
                "state", state.name());

        VelocityMonitorService monitorService = monitorServiceSupplier.get();
        if (monitorService != null) {
            monitorService.sendPlayerUpdate(player);
        }

        if (state == ProudAuthMonitorState.AUTHENTICATED && isAuthenticatedOnAuthEntry(player, sourceServer)) {
            redirectPostAuthIfNeeded(player, sourceServer, routingSupplier.get(), "auth_sync_state_redirect_post_auth");
        }
    }

    private void handleAuthCompleted(Player player, String sourceServer) {
        boolean alreadyAuthenticated = resolvedPlayerStore.find(player.getUsername())
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::networkAuthenticated)
                .orElse(false);
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();

        if (alreadyAuthenticated) {
            monitorAuthStateStore.update(player.getUsername(), ProudAuthMonitorState.AUTHENTICATED);
            sendMonitorUpdate(player);
            debugEvent("auth_sync_completed_duplicate_ignored",
                    "player", player.getUsername(),
                    "server", sourceServer);
            redirectPostAuthIfNeeded(player, sourceServer, routing, "auth_sync_completed_duplicate_redirect_post_auth");
            return;
        }

        resolvedPlayerStore.markNetworkAuthenticated(player.getUsername(), true);
        monitorAuthStateStore.update(player.getUsername(), ProudAuthMonitorState.AUTHENTICATED);
        sendMonitorUpdate(player);

        debugEvent("auth_sync_completed",
                "player", player.getUsername(),
                "server", sourceServer);

        redirectPostAuthIfNeeded(player, sourceServer, routing, "auth_sync_redirect_post_auth");
    }

    private void handleAuthInvalidated(Player player, String sourceServer) {
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        String authEntryServer = routing.hasAuthEntryServer() ? routing.authEntryServer() : sourceServer;

        resolvedPlayerStore.markNetworkAuthenticated(player.getUsername(), false);
        resolvedPlayerStore.rememberAuthEntryServer(player.getUsername(), authEntryServer);
        monitorAuthStateStore.update(player.getUsername(), ProudAuthMonitorState.WAITING_LOGIN);
        sendMonitorUpdate(player);

        debugEvent("auth_sync_invalidated",
                "player", player.getUsername(),
                "server", sourceServer,
                "auth_entry_server", authEntryServer);

        if (!routing.hasAuthEntryServer() || authEntryServer.equalsIgnoreCase(sourceServer)) {
            return;
        }

        proxyServer.getServer(authEntryServer).ifPresent(targetServer -> {
            resolvedPlayerStore.rememberAllowedAuthEntry(player.getUsername(), authEntryServer);
            player.createConnectionRequest(targetServer).fireAndForget();
            debugEvent("auth_sync_redirect_auth_entry",
                    "player", player.getUsername(),
                    "from", sourceServer,
                    "target", authEntryServer);
        });
    }

    private void handleAuthNotice(Player player, String sourceServer, String messageKey) {
        if (messageKey == null || messageKey.isBlank()) {
            return;
        }

        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        if (!routing.hasPostAuthServer() || routing.postAuthServer().equalsIgnoreCase(sourceServer)) {
            langSupplier.get().send(player, messageKey);
            debugEvent("auth_sync_notice_sent_immediate",
                    "player", player.getUsername(),
                    "server", sourceServer,
                    "message_key", messageKey);
            return;
        }

        resolvedPlayerStore.rememberPendingNotice(player.getUsername(), messageKey, routing.postAuthServer());
        debugEvent("auth_sync_notice_scheduled",
                "player", player.getUsername(),
                "server", sourceServer,
                "target", routing.postAuthServer(),
                "message_key", messageKey);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        resolvedPlayerStore.forget(event.getPlayer().getUsername());
        monitorAuthStateStore.forget(event.getPlayer().getUsername());

        debugEvent("server_switch_cache_cleared",
                "player", event.getPlayer().getUsername());
    }

    private void sendMonitorUpdate(Player player) {
        VelocityMonitorService monitorService = monitorServiceSupplier.get();

        if (monitorService != null) {
            monitorService.sendPlayerUpdate(player);
        }
    }

    private boolean isAuthenticatedOnAuthEntry(Player player, String sourceServer) {
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        if (!routing.hasAuthEntryServer() || !routing.authEntryServer().equalsIgnoreCase(sourceServer)) {
            return false;
        }
        return resolvedPlayerStore.find(player.getUsername())
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::networkAuthenticated)
                .orElse(false);
    }

    private void redirectPostAuthIfNeeded(
            Player player,
            String sourceServer,
            ProudAuthNetworkConfig.Routing routing,
            String eventName
    ) {
        if (!routing.hasPostAuthServer() || routing.postAuthServer().equalsIgnoreCase(sourceServer)) {
            return;
        }

        proxyServer.getServer(routing.postAuthServer()).ifPresent(targetServer -> {
            boolean legacyClient = isLegacyClient(player.getUsername());
            int delayMs = redirectDelayMillis(player.getUsername(), routing);

            if (delayMs <= 0) {
                player.createConnectionRequest(targetServer).fireAndForget();
                debugEvent(eventName,
                        "player", player.getUsername(),
                        "from", sourceServer,
                        "target", routing.postAuthServer(),
                        "delay_ms", 0,
                        "legacy_client", legacyClient);
                return;
            }

            proxyServer.getScheduler()
                    .buildTask(pluginOwner, () -> executePostAuthRedirect(player.getUsername(), sourceServer, targetServer, delayMs))
                    .delay(delayMs, TimeUnit.MILLISECONDS)
                    .schedule();

            debugEvent(eventName + "_scheduled",
                    "player", player.getUsername(),
                    "from", sourceServer,
                    "target", routing.postAuthServer(),
                    "delay_ms", delayMs,
                    "legacy_client", legacyClient);
        });
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }

    private void executePostAuthRedirect(String username, String sourceServer, RegisteredServer targetServer, int delayMs) {
        proxyServer.getPlayer(username).ifPresent(player -> {
            player.createConnectionRequest(targetServer).fireAndForget();
            debugEvent("auth_sync_redirect_post_auth",
                    "player", username,
                    "from", sourceServer,
                    "target", targetServer.getServerInfo().getName(),
                    "delay_ms", delayMs,
                    "legacy_client", isLegacyClient(username));
        });
    }

    private int redirectDelayMillis(String username, ProudAuthNetworkConfig.Routing routing) {
        return isLegacyClient(username)
                ? routing.legacyPostAuthRedirectDelayMs()
                : routing.postAuthRedirectDelayMs();
    }

    private boolean isLegacyClient(String username) {
        return resolvedPlayerStore.find(username)
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::legacyClient)
                .orElse(false);
    }
}
