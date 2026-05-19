package com.monkey.proudAuth.network;

import com.monkey.proudAuth.auth.ResolvedLogin;
import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorState;
import com.monkey.proudAuth.common.network.ProudAuthNetworkChannel;
import com.monkey.proudAuth.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BackendNetworkSyncService {

    private static final int AUTH_COMPLETED_RETRY_ATTEMPTS = 4;
    private static final long AUTH_COMPLETED_RETRY_INTERVAL_TICKS = 5L;

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
                resolvedLogin.networkAuthenticated(),
                resolvedLogin.legacyClient(),
                resolvedLogin.proxyUsername()
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
        JoinContext joinContext = contexts.get(player.getUniqueId());
        if (joinContext != null && joinContext.legacyClient()) {
            sendWithRetries(player, ProudAuthNetworkChannel.AUTH_COMPLETED, AUTH_COMPLETED_RETRY_ATTEMPTS, AUTH_COMPLETED_RETRY_INTERVAL_TICKS);
        } else {
            send(player, ProudAuthNetworkChannel.AUTH_COMPLETED);
        }
        contexts.computeIfPresent(player.getUniqueId(), (ignored, context) -> context.withNetworkAuthenticated(true));
    }

    public void notifyAuthInvalidated(Player player) {
        if (!isVelocityProxyMode()) {
            return;
        }
        send(player, ProudAuthNetworkChannel.AUTH_INVALIDATED);
        contexts.computeIfPresent(player.getUniqueId(), (ignored, context) -> context.afterInvalidation(pluginConfig.serverId()));
    }

    public void notifyMonitorState(Player player, ProudAuthMonitorState state) {
        if (!isVelocityProxyMode()) {
            return;
        }

        send(player, ProudAuthNetworkChannel.AUTH_STATE_UPDATE, state.name());
    }

    public void notifyAuthNotice(Player player, String messageKey) {
        if (!isVelocityProxyMode()) {
            return;
        }

        send(player, ProudAuthNetworkChannel.AUTH_NOTICE, messageKey);
    }

    private void sendWithRetries(Player player, String messageType, int attempts, long intervalTicks) {
        int safeAttempts = Math.max(1, attempts);
        long safeIntervalTicks = Math.max(1L, intervalTicks);
        debugEvent("backend_network_sync_scheduled",
                "player", player.getName(),
                "server_id", pluginConfig.serverId(),
                "message_type", messageType,
                "attempts", safeAttempts,
                "interval_ticks", safeIntervalTicks);
        for (int attempt = 0; attempt < safeAttempts; attempt++) {
            long delay = safeIntervalTicks * attempt;
            int currentAttempt = attempt + 1;
            Bukkit.getScheduler().runTaskLater(plugin, () -> send(player, messageType, currentAttempt, safeAttempts), delay);
        }
    }

    private void send(Player player, String messageType) {
        send(player, messageType, "", 1, 1);
    }

    private void send(Player player, String messageType, String payload) {
        send(player, messageType, payload, 1, 1);
    }

    private void send(Player player, String messageType, int attempt, int totalAttempts) {
        send(player, messageType, "", attempt, totalAttempts);
    }

    private void send(Player player, String messageType, String payload, int attempt, int totalAttempts) {
        if (!player.isOnline()) {
            return;
        }

        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(byteStream);
            output.writeUTF(messageType);
            output.writeUTF(syncUsername(player));
            output.writeUTF(pluginConfig.serverId());
            output.writeUTF(payload == null ? "" : payload);

            player.sendPluginMessage(plugin, ProudAuthNetworkChannel.CHANNEL_ID, byteStream.toByteArray());

            debugEvent("backend_network_sync_sent",
                    "player", player.getName(),
                    "proxy_player", syncUsername(player),
                    "server_id", pluginConfig.serverId(),
                    "message_type", messageType,
                    "payload", payload == null ? "" : payload,
                    "attempt", attempt,
                    "total_attempts", totalAttempts);
        } catch (IOException exception) {
            logger.error("Unable to send ProudAuth sync message to proxy.", exception);
        }
    }

    private boolean isVelocityProxyMode() {
        return pluginConfig.settings().proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY;
    }

    private String syncUsername(Player player) {
        JoinContext context = contexts.get(player.getUniqueId());
        if (context == null || context.proxyUsername() == null || context.proxyUsername().isBlank()) {
            return player.getName();
        }
        return context.proxyUsername();
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), DebugChannel.BRIDGE_FLOW, eventName, keyValues);
    }

    private record JoinContext(
            BridgeJoinMode joinMode,
            String authEntryServer,
            boolean authEntryEnforced,
            String postAuthServer,
            boolean networkAuthenticated,
            boolean legacyClient,
            String proxyUsername
    ) {
        private JoinContext withNetworkAuthenticated(boolean authenticated) {
            return new JoinContext(joinMode, authEntryServer, authEntryEnforced, postAuthServer, authenticated, legacyClient, proxyUsername);
        }

        private JoinContext afterInvalidation(String currentServerId) {
            if (authEntryEnforced && authEntryServer != null && !authEntryServer.equalsIgnoreCase(currentServerId)) {
                return new JoinContext(joinMode, authEntryServer, true, postAuthServer, false, legacyClient, proxyUsername);
            }
            return new JoinContext(BridgeJoinMode.AUTH_ENTRY, currentServerId, false, postAuthServer, false, legacyClient, proxyUsername);
        }
    }
}
