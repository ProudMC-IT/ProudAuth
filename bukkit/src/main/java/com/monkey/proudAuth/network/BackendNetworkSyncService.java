package com.monkey.proudAuth.network;

import com.monkey.proudAuth.auth.ResolvedLogin;
import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.network.ProudAuthNetworkChannel;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendNetworkSyncService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final ProudAuthConsoleLogger logger;
    private final Map<UUID, JoinContext> contexts = new ConcurrentHashMap<>();

    public BackendNetworkSyncService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            ProudAuthConsoleLogger logger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.logger = logger;
    }

    public void registerChannels() {
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, ProudAuthNetworkChannel.CHANNEL_ID);
    }

    public void unregisterChannels() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, ProudAuthNetworkChannel.CHANNEL_ID);
    }

    public void remember(Player player, ResolvedLogin resolvedLogin) {
        contexts.put(player.getUniqueId(), new JoinContext(
                resolvedLogin.joinMode(),
                resolvedLogin.authEntryServer(),
                resolvedLogin.authEntryEnforced(),
                resolvedLogin.postAuthServer(),
                resolvedLogin.networkAuthenticated()
        ));
    }

    public void forget(UUID playerUuid) {
        contexts.remove(playerUuid);
    }

    public boolean allowsManualAuthentication(Player player) {
        JoinContext context = contexts.get(player.getUniqueId());
        return context == null || context.joinMode() == BridgeJoinMode.AUTH_ENTRY;
    }

    public boolean isNetworkTransfer(Player player) {
        JoinContext context = contexts.get(player.getUniqueId());
        return context != null && context.joinMode() == BridgeJoinMode.NETWORK_TRANSFER;
    }

    public boolean isTrustedNetworkTransfer(Player player) {
        JoinContext context = contexts.get(player.getUniqueId());
        return context != null
                && context.joinMode() == BridgeJoinMode.NETWORK_TRANSFER
                && context.networkAuthenticated();
    }

    public void notifyAuthCompleted(Player player) {
        if (!isVelocityProxyMode()) {
            return;
        }
        send(player, ProudAuthNetworkChannel.AUTH_COMPLETED);
        contexts.computeIfPresent(player.getUniqueId(), (ignored, context) -> context.withNetworkAuthenticated(true));
    }

    public void notifyAuthInvalidated(Player player) {
        if (!isVelocityProxyMode()) {
            return;
        }
        send(player, ProudAuthNetworkChannel.AUTH_INVALIDATED);
        contexts.computeIfPresent(player.getUniqueId(), (ignored, context) -> context.afterInvalidation(pluginConfig.serverId()));
    }

    private void send(Player player, String messageType) {
        if (!player.isOnline()) {
            return;
        }
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(byteStream);
            output.writeUTF(messageType);
            output.writeUTF(player.getName());
            output.writeUTF(pluginConfig.serverId());
            player.sendPluginMessage(plugin, ProudAuthNetworkChannel.CHANNEL_ID, byteStream.toByteArray());
            debugEvent("backend_network_sync_sent",
                    "player", player.getName(),
                    "server_id", pluginConfig.serverId(),
                    "message_type", messageType);
        } catch (IOException exception) {
            logger.error("Impossibile inviare il sync ProudAuth al proxy.", exception);
        }
    }

    private boolean isVelocityProxyMode() {
        return pluginConfig.settings().proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY;
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }

    private record JoinContext(
            BridgeJoinMode joinMode,
            String authEntryServer,
            boolean authEntryEnforced,
            String postAuthServer,
            boolean networkAuthenticated
    ) {
        private JoinContext withNetworkAuthenticated(boolean authenticated) {
            return new JoinContext(joinMode, authEntryServer, authEntryEnforced, postAuthServer, authenticated);
        }

        private JoinContext afterInvalidation(String currentServerId) {
            if (authEntryEnforced && authEntryServer != null && !authEntryServer.equalsIgnoreCase(currentServerId)) {
                return new JoinContext(joinMode, authEntryServer, true, postAuthServer, false);
            }
            return new JoinContext(BridgeJoinMode.AUTH_ENTRY, currentServerId, false, postAuthServer, false);
        }
    }
}
