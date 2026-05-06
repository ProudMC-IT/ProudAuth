package com.monkey.proudAuth.velocity.monitor;

import com.monkey.proudAuth.common.monitor.MonitorIdentity;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorConstants;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VelocityMonitorSnapshotBuilder {

    private final ProxyServer proxyServer;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final Set<UUID> lockedPlayers;
    private final VelocityMonitorAuthStateStore authStateStore;

    public VelocityMonitorSnapshotBuilder(
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            Set<UUID> lockedPlayers,
            VelocityMonitorAuthStateStore authStateStore
    ) {
        this.proxyServer = proxyServer;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.lockedPlayers = lockedPlayers;
        this.authStateStore = authStateStore;
    }

    public Map<String, Object> build(String sessionId, String createdBy, MonitorIdentity identity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("networkId", identity.networkId());
        snapshot.put("networkName", ProudAuthMonitorConstants.DEFAULT_NETWORK_NAME);
        snapshot.put("sessionId", sessionId);
        snapshot.put("createdBy", createdBy);
        snapshot.put("createdAt", System.currentTimeMillis());
        snapshot.put("servers", servers(identity));
        snapshot.put("players", players());

        return snapshot;
    }

    public Map<String, Object> player(Player player) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", player.getUniqueId().toString());
        payload.put("name", player.getUsername());
        payload.put("serverId", currentServer(player));
        payload.put("authState", authState(player));
        payload.put("ping", Math.max(0, (int) player.getPing()));
        payload.put("joinedAt", System.currentTimeMillis());
        payload.put("permissions", List.of());
        payload.put("groups", groups(player));

        return payload;
    }

    public Map<String, Object> serverUpdate(RegisteredServer server, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", server.getServerInfo().getName());
        payload.put("name", server.getServerInfo().getName());
        payload.put("type", "BACKEND");
        payload.put("status", status);
        payload.put("onlinePlayers", server.getPlayersConnected().size());
        payload.put("maxPlayers", 0);

        return payload;
    }

    private List<Map<String, Object>> servers(MonitorIdentity identity) {
        List<Map<String, Object>> servers = new ArrayList<>();

        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("id", identity.proxyId());
        proxy.put("name", "Velocity");
        proxy.put("type", "PROXY");
        proxy.put("status", "ONLINE");
        proxy.put("onlinePlayers", 0);
        proxy.put("maxPlayers", 0);
        servers.add(proxy);

        for (RegisteredServer server : proxyServer.getAllServers()) {
            servers.add(serverUpdate(server, "ONLINE"));
        }

        return servers;
    }

    private List<Map<String, Object>> players() {
        List<Map<String, Object>> players = new ArrayList<>();

        for (Player player : proxyServer.getAllPlayers()) {
            players.add(player(player));
        }

        return players;
    }

    private String currentServer(Player player) {
        return player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("unknown");
    }

    private String authState(Player player) {
        if (lockedPlayers.contains(player.getUniqueId())) {
            return "LOCKED";
        }

        return authStateStore.find(player.getUsername())
                .map(Enum::name)
                .orElseGet(() -> resolvedPlayerStore.find(player.getUsername())
                        .map(resolvedPlayer -> resolvedPlayer.networkAuthenticated()
                                ? "AUTHENTICATED"
                                : "WAITING_LOGIN")
                        .orElse("UNKNOWN"));
    }

    private List<String> groups(Player player) {
        return resolvedPlayerStore.find(player.getUsername())
                .map(resolvedPlayer -> {
                    if (resolvedPlayer.accountType() == null) {
                        return List.of("unknown");
                    }

                    return List.of(resolvedPlayer.accountType().name().toLowerCase(Locale.ROOT));
                })
                .orElse(List.of("unknown"));
    }
}