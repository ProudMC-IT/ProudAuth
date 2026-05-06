package com.monkey.proudAuth.velocity.monitor;

import com.monkey.proudAuth.common.monitor.MonitorIdentity;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorConstants;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class VelocityMonitorSnapshotBuilder {

    private final ProxyServer proxyServer;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;

    public VelocityMonitorSnapshotBuilder(
            ProxyServer proxyServer,
            VelocityResolvedPlayerStore resolvedPlayerStore
    ) {
        this.proxyServer = proxyServer;
        this.resolvedPlayerStore = resolvedPlayerStore;
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
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer = resolvedPlayerStore.find(player.getUsername());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("uuid", player.getUniqueId().toString());
        payload.put("name", player.getUsername());
        payload.put("serverId", currentServer(player));
        payload.put("authState", authState(player, resolvedPlayer));
        payload.put("ping", Math.max(0, (int) player.getPing()));
        payload.put("joinedAt", System.currentTimeMillis());
        payload.put("permissions", List.of());
        payload.put("groups", groups(resolvedPlayer));

        payload.put("accountType", accountType(resolvedPlayer));
        payload.put("sessionType", sessionType(resolvedPlayer));
        payload.put("protocolVersion", protocolVersion(resolvedPlayer));
        payload.put("clientBrand", clientBrand(player));
        payload.put("legacyClient", legacyClient(resolvedPlayer));
        payload.put("premiumNameDetected", premiumNameDetected(resolvedPlayer));
        payload.put("premiumVerified", premiumVerified(resolvedPlayer));
        payload.put("premiumEnforced", premiumEnforced(resolvedPlayer));

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

    private String authState(Player player, Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(profile -> profile.networkAuthenticated() ? "AUTHENTICATED" : "WAITING_LOGIN")
                .orElse("UNKNOWN");
    }

    private List<String> groups(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(profile -> List.of(accountType(profile).toLowerCase(Locale.ROOT)))
                .orElse(List.of("unknown"));
    }

    private String accountType(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(this::accountType)
                .orElse("UNKNOWN");
    }

    private String accountType(VelocityResolvedPlayerStore.ResolvedPlayer resolvedPlayer) {
        if (resolvedPlayer.accountType() == null) {
            return "UNKNOWN";
        }

        return resolvedPlayer.accountType().name();
    }

    private String sessionType(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(profile -> accountType(profile).toLowerCase(Locale.ROOT))
                .orElse("unknown");
    }

    private String protocolVersion(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::protocolVersion)
                .filter(value -> value != null && !value.isBlank())
                .orElse("unknown");
    }

    private boolean legacyClient(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::legacyClient)
                .orElse(false);
    }

    private boolean premiumNameDetected(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::premiumNameDetected)
                .orElse(false);
    }

    private boolean premiumVerified(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::premiumVerified)
                .orElse(false);
    }

    private boolean premiumEnforced(Optional<VelocityResolvedPlayerStore.ResolvedPlayer> resolvedPlayer) {
        return resolvedPlayer
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::premiumEnforced)
                .orElse(false);
    }

    private String clientBrand(Player player) {
        try {
            Method method = player.getClass().getMethod("getClientBrand");
            Object result = method.invoke(player);

            if (result instanceof Optional<?> optional) {
                Object value = optional.orElse(null);
                return value == null || String.valueOf(value).isBlank()
                        ? "unknown"
                        : String.valueOf(value);
            }

            if (result != null && !String.valueOf(result).isBlank()) {
                return String.valueOf(result);
            }
        } catch (ReflectiveOperationException ignored) {
        }

        return "unknown";
    }
}