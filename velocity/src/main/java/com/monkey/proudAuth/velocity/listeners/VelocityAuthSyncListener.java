package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.network.ProudAuthNetworkChannel;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Supplier;

public final class VelocityAuthSyncListener {

    private static final MinecraftChannelIdentifier CHANNEL_IDENTIFIER =
            MinecraftChannelIdentifier.from(ProudAuthNetworkChannel.CHANNEL_ID);

    private final ProxyServer proxyServer;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final Supplier<ProudAuthNetworkConfig.Routing> routingSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityAuthSyncListener(
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            Supplier<ProudAuthNetworkConfig.Routing> routingSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.proxyServer = proxyServer;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.routingSupplier = routingSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
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

            if (ProudAuthNetworkChannel.AUTH_COMPLETED.equals(type)) {
                handleAuthCompleted(player.get(), sourceServer);
                return;
            }
            if (ProudAuthNetworkChannel.AUTH_INVALIDATED.equals(type)) {
                handleAuthInvalidated(player.get(), sourceServer);
            }
        } catch (IOException exception) {
            debugEvent("auth_sync_decode_error",
                    "error", exception.getMessage());
        }
    }

    private void handleAuthCompleted(Player player, String sourceServer) {
        resolvedPlayerStore.markNetworkAuthenticated(player.getUsername(), true);
        debugEvent("auth_sync_completed",
                "player", player.getUsername(),
                "server", sourceServer);

        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        if (!routing.hasPostAuthServer() || routing.postAuthServer().equalsIgnoreCase(sourceServer)) {
            return;
        }

        proxyServer.getServer(routing.postAuthServer()).ifPresent(targetServer -> {
            player.createConnectionRequest(targetServer).fireAndForget();
            debugEvent("auth_sync_redirect_post_auth",
                    "player", player.getUsername(),
                    "from", sourceServer,
                    "target", routing.postAuthServer());
        });
    }

    private void handleAuthInvalidated(Player player, String sourceServer) {
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        String authEntryServer = routing.hasAuthEntryServer() ? routing.authEntryServer() : sourceServer;
        resolvedPlayerStore.markNetworkAuthenticated(player.getUsername(), false);
        resolvedPlayerStore.rememberAuthEntryServer(player.getUsername(), authEntryServer);
        debugEvent("auth_sync_invalidated",
                "player", player.getUsername(),
                "server", sourceServer,
                "auth_entry_server", authEntryServer);

        if (!routing.hasAuthEntryServer() || authEntryServer.equalsIgnoreCase(sourceServer)) {
            return;
        }

        proxyServer.getServer(authEntryServer).ifPresent(targetServer -> {
            player.createConnectionRequest(targetServer).fireAndForget();
            debugEvent("auth_sync_redirect_auth_entry",
                    "player", player.getUsername(),
                    "from", sourceServer,
                    "target", authEntryServer);
        });
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }
}
