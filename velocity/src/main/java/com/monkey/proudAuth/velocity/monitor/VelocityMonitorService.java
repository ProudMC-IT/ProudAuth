package com.monkey.proudAuth.velocity.monitor;

import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.monitor.MonitorIdentity;
import com.monkey.proudAuth.common.monitor.MonitorIdentityStore;
import com.monkey.proudAuth.common.monitor.MonitorJsonReader;
import com.monkey.proudAuth.common.monitor.MonitorSessionIdGenerator;
import com.monkey.proudAuth.common.monitor.MonitorSocketClient;
import com.monkey.proudAuth.common.monitor.ProudAuthMonitorConstants;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class VelocityMonitorService {

    private static final Component MONITOR_PREFIX = Component.text("[ProudAuth Monitor] ");

    private final Object pluginOwner;
    private final ProxyServer proxyServer;
    private final ProudAuthConsoleLogger logger;
    private final MonitorIdentity identity;
    private final MonitorSessionIdGenerator sessionIdGenerator = new MonitorSessionIdGenerator();
    private final VelocityMonitorSnapshotBuilder snapshotBuilder;
    private final MonitorSocketClient socketClient;
    private final Set<UUID> lockedPlayers = new HashSet<>();

    private String currentSessionId;
    private String currentCreatedBy;
    private long currentCreatedAt;
    private ScheduledTask sessionCleanupTask;
    private ScheduledTask serverStatusTask;

    public VelocityMonitorService(
            Object pluginOwner,
            ProxyServer proxyServer,
            Path dataDirectory,
            ProudAuthConsoleLogger logger,
            VelocityResolvedPlayerStore resolvedPlayerStore
    ) {
        this.pluginOwner = pluginOwner;
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.identity = new MonitorIdentityStore(dataDirectory, logger).loadOrCreate();
        this.snapshotBuilder = new VelocityMonitorSnapshotBuilder(proxyServer, resolvedPlayerStore, lockedPlayers);
        this.socketClient = new MonitorSocketClient(logger, this::handleIncomingMessage);
    }

    public CompletableFuture<String> open(String createdBy) {
        closeSession("REPLACED", "Session replaced by a new monitor session.");

        String sessionId = sessionIdGenerator.generate();
        currentSessionId = sessionId;
        currentCreatedBy = createdBy;
        currentCreatedAt = System.currentTimeMillis();

        logger.info("Opening monitor session " + sessionId + " createdBy=" + createdBy);

        return socketClient.connect(sessionId, identity)
                .thenApply(ignored -> {
                    socketClient.send("snapshot", snapshotBuilder.build(sessionId, createdBy, identity));
                    scheduleSessionCleanup(sessionId);
                    startServerStatusTask();
                    return ProudAuthMonitorConstants.PANEL_URL + "/monitor/" + sessionId;
                });
    }

    public CompletableFuture<String> restart(String createdBy) {
        closeSession("RESTARTED", "Monitor session restarted.");
        return open(createdBy);
    }

    public boolean closeSession(String reason, String message) {
        if (currentSessionId == null) {
            return false;
        }

        String closedSessionId = currentSessionId;

        logger.info("Closing monitor session " + closedSessionId + " reason=" + reason);

        socketClient.send("session_closed", Map.of(
                "sessionId", closedSessionId,
                "reason", reason,
                "message", message,
                "closedAt", System.currentTimeMillis()
        ));

        currentSessionId = null;
        currentCreatedBy = null;
        currentCreatedAt = 0L;

        if (sessionCleanupTask != null) {
            sessionCleanupTask.cancel();
            sessionCleanupTask = null;
        }

        if (serverStatusTask != null) {
            serverStatusTask.cancel();
            serverStatusTask = null;
        }

        socketClient.close();
        return true;
    }

    public void shutdown() {
        closeSession("PROXY_SHUTDOWN", "Velocity proxy is shutting down.");
    }

    public boolean active() {
        return currentSessionId != null && socketClient.connected();
    }

    public String currentSessionId() {
        return currentSessionId;
    }

    public String currentCreatedBy() {
        return currentCreatedBy;
    }

    public long currentCreatedAt() {
        return currentCreatedAt;
    }

    public String currentUrl() {
        if (currentSessionId == null) {
            return "";
        }

        return ProudAuthMonitorConstants.PANEL_URL + "/monitor/" + currentSessionId;
    }

    public boolean unlockAccount(String target) {
        Optional<UUID> uuid = resolveUuid(target);

        if (uuid.isEmpty()) {
            return false;
        }

        boolean removed = lockedPlayers.remove(uuid.get());

        if (removed) {
            logger.info("Monitor account unlock applied from proxy command: uuid=" + uuid.get());
        }

        return removed;
    }

    public boolean isLocked(String target) {
        Optional<UUID> uuid = resolveUuid(target);
        return uuid.filter(lockedPlayers::contains).isPresent();
    }

    public void sendPlayerJoin(Player player) {
        if (lockedPlayers.contains(player.getUniqueId())) {
            player.disconnect(MONITOR_PREFIX.append(Component.text("Your account is locked by staff.")));
            return;
        }

        if (!active()) {
            return;
        }

        socketClient.send("player_join", snapshotBuilder.player(player));
        sendCurrentServerUpdate(player);
    }

    public void sendPlayerQuit(Player player) {
        if (!active()) {
            return;
        }

        socketClient.send("player_quit", Map.of(
                "uuid", player.getUniqueId().toString()
        ));
        sendAllServerUpdates();
    }

    public void sendPlayerMove(Player player) {
        if (!active()) {
            return;
        }

        player.getCurrentServer().ifPresent(connection -> {
            socketClient.send("player_move", Map.of(
                    "uuid", player.getUniqueId().toString(),
                    "serverId", connection.getServerInfo().getName()
            ));

            socketClient.send("player_update", snapshotBuilder.player(player));
            socketClient.send("server_update", snapshotBuilder.serverUpdate(connection.getServer(), "ONLINE"));
        });
    }

    private void sendCurrentServerUpdate(Player player) {
        player.getCurrentServer().ifPresent(connection ->
                socketClient.send("server_update", snapshotBuilder.serverUpdate(connection.getServer(), "ONLINE"))
        );
    }

    private void sendAllServerUpdates() {
        for (RegisteredServer server : proxyServer.getAllServers()) {
            sendBackendStatus(server);
        }
    }

    private void scheduleSessionCleanup(String sessionId) {
        sessionCleanupTask = proxyServer.getScheduler()
                .buildTask(pluginOwner, () -> {
                    if (!sessionId.equals(currentSessionId)) {
                        return;
                    }

                    closeSession("EXPIRED", "Monitor session expired.");
                })
                .delay(ProudAuthMonitorConstants.SESSION_TTL.toMinutes(), TimeUnit.MINUTES)
                .schedule();
    }

    private void startServerStatusTask() {
        if (serverStatusTask != null) {
            serverStatusTask.cancel();
        }

        serverStatusTask = proxyServer.getScheduler()
                .buildTask(pluginOwner, this::sendAllServerUpdates)
                .repeat(10, TimeUnit.SECONDS)
                .schedule();

        sendAllServerUpdates();
    }

    private void sendBackendStatus(RegisteredServer server) {
        server.ping().whenComplete((ping, exception) -> {
            String status = exception == null ? "ONLINE" : "OFFLINE";
            socketClient.send("server_update", snapshotBuilder.serverUpdate(server, status));
        });
    }

    private void handleIncomingMessage(String rawMessage) {
        String rootType = MonitorJsonReader.string(rawMessage, "type");

        if (!"action_request".equals(rootType)) {
            return;
        }

        String payload = MonitorJsonReader.objectAfter(rawMessage, "payload");
        String actionId = MonitorJsonReader.string(payload, "actionId");
        String actionType = MonitorJsonReader.string(payload, "type");
        String targetUuid = MonitorJsonReader.string(payload, "targetUuid");
        String actionPayload = MonitorJsonReader.objectAfter(payload, "payload");

        logger.info("Monitor action received: " + actionType + " target=" + targetUuid + " actionId=" + actionId);

        proxyServer.getScheduler()
                .buildTask(pluginOwner, () -> executeAction(actionId, actionType, targetUuid, actionPayload))
                .schedule();
    }

    private void executeAction(String actionId, String actionType, String targetUuid, String actionPayload) {
        Optional<Player> player = findPlayer(targetUuid);

        if (player.isEmpty() && requiresOnlinePlayer(actionType)) {
            logger.warn("Monitor action failed: target player is offline target=" + targetUuid);
            sendActionResult(actionId, "FAILED", "Player offline");
            return;
        }

        logger.info("Executing monitor action " + actionType + " target=" + targetUuid);

        switch (actionType) {
            case "SEND_MESSAGE" -> executeSendMessage(actionId, player.get(), actionPayload);
            case "REQUEST_REAUTH" -> executeRequestReauth(actionId, player.get(), actionPayload);
            case "FORCE_LOGOUT" -> executeForceLogout(actionId, player.get(), actionPayload);
            case "KICK_PLAYER" -> executeKick(actionId, player.get(), actionPayload);
            case "MOVE_SERVER" -> executeMoveServer(actionId, player.get(), actionPayload);
            case "LOCK_ACCOUNT" -> executeLockAccount(actionId, targetUuid, player, actionPayload);
            case "UNLOCK_ACCOUNT" -> executeUnlockAccount(actionId, targetUuid);
            default -> {
                logger.warn("Unsupported monitor action: " + actionType);
                sendActionResult(actionId, "FAILED", "Unsupported action");
            }
        }
    }

    private boolean requiresOnlinePlayer(String actionType) {
        return switch (actionType) {
            case "SEND_MESSAGE", "REQUEST_REAUTH", "FORCE_LOGOUT", "KICK_PLAYER", "MOVE_SERVER" -> true;
            default -> false;
        };
    }

    private void executeSendMessage(String actionId, Player player, String payload) {
        String message = MonitorJsonReader.string(payload, "message");

        if (message.isBlank()) {
            logger.warn("Monitor message failed: empty message");
            sendActionResult(actionId, "FAILED", "Empty message");
            return;
        }

        player.sendMessage(MONITOR_PREFIX.append(Component.text(message)));
        logger.info("Monitor message sent to " + player.getUsername());
        sendActionResult(actionId, "SUCCESS", "Message sent");
    }

    private void executeRequestReauth(String actionId, Player player, String payload) {
        String reason = MonitorJsonReader.string(payload, "reason");

        if (reason.isBlank()) {
            reason = "Staff requested a new authentication.";
        }

        logger.info("Monitor re-auth requested: player=" + player.getUsername());
        player.disconnect(MONITOR_PREFIX.append(Component.text(reason)));
        sendActionResult(actionId, "SUCCESS", "Re-auth requested");
    }

    private void executeForceLogout(String actionId, Player player, String payload) {
        String reason = MonitorJsonReader.string(payload, "reason");

        if (reason.isBlank()) {
            reason = "You have been logged out by staff.";
        }

        logger.info("Monitor force logout requested: player=" + player.getUsername());
        player.disconnect(MONITOR_PREFIX.append(Component.text(reason)));
        sendActionResult(actionId, "SUCCESS", "Player logged out");
    }

    private void executeKick(String actionId, Player player, String payload) {
        String reason = MonitorJsonReader.string(payload, "reason");

        if (reason.isBlank()) {
            reason = "Disconnected by staff.";
        }

        logger.info("Monitor kick requested: player=" + player.getUsername() + " reason=" + reason);
        player.disconnect(MONITOR_PREFIX.append(Component.text(reason)));
        sendActionResult(actionId, "SUCCESS", "Player kicked");
    }

    private void executeMoveServer(String actionId, Player player, String payload) {
        String serverId = MonitorJsonReader.string(payload, "serverId");

        logger.info("Monitor move requested: player=" + player.getUsername() + " server=" + serverId);

        if (serverId.isBlank()) {
            logger.warn("Monitor move failed: empty serverId");
            sendActionResult(actionId, "FAILED", "Invalid server");
            return;
        }

        Optional<RegisteredServer> server = proxyServer.getServer(serverId);

        if (server.isEmpty()) {
            logger.warn("Monitor move failed: server not found " + serverId);
            sendActionResult(actionId, "FAILED", "Server not found: " + serverId);
            return;
        }

        String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("");

        if (currentServer.equalsIgnoreCase(serverId)) {
            logger.info("Monitor move ignored: player already connected to " + serverId);
            sendActionResult(actionId, "SUCCESS", "Player already connected to this server");
            return;
        }

        player.createConnectionRequest(server.get()).connect().whenComplete((result, exception) -> {
            if (exception != null) {
                logger.error("Monitor move error: " + player.getUsername() + " -> " + serverId, exception);
                sendActionResult(actionId, "FAILED", "Move error: " + exception.getMessage());
                return;
            }

            if (!result.isSuccessful()) {
                logger.warn("Monitor move failed: "
                        + player.getUsername()
                        + " -> "
                        + serverId
                        + " result="
                        + result.getStatus());
                sendActionResult(actionId, "FAILED", "Move failed: " + result.getStatus());
                return;
            }

            logger.info("Monitor move completed: " + player.getUsername() + " -> " + serverId);
            sendActionResult(actionId, "SUCCESS", "Player moved to " + serverId);

            socketClient.send("player_move", Map.of(
                    "uuid", player.getUniqueId().toString(),
                    "serverId", serverId
            ));

            socketClient.send("player_update", snapshotBuilder.player(player));
            socketClient.send("server_update", snapshotBuilder.serverUpdate(server.get(), "ONLINE"));
        });
    }

    private void executeLockAccount(String actionId, String targetUuid, Optional<Player> player, String payload) {
        Optional<UUID> uuid = parseUuid(targetUuid);

        if (uuid.isEmpty()) {
            sendActionResult(actionId, "FAILED", "Invalid player UUID");
            return;
        }

        String rawReason = MonitorJsonReader.string(payload, "reason");
        String reason = rawReason.isBlank()
                ? "Your account has been locked by staff."
                : rawReason;

        lockedPlayers.add(uuid.get());

        player.ifPresent(onlinePlayer -> {
            socketClient.send("player_update", snapshotBuilder.player(onlinePlayer));
            onlinePlayer.disconnect(MONITOR_PREFIX.append(Component.text(reason)));
        });

        logger.info("Monitor account lock applied: uuid=" + targetUuid);
        sendActionResult(actionId, "SUCCESS", "Account locked");
    }

    private void executeUnlockAccount(String actionId, String targetUuid) {
        Optional<UUID> uuid = parseUuid(targetUuid);

        if (uuid.isEmpty()) {
            sendActionResult(actionId, "FAILED", "Invalid player UUID");
            return;
        }

        boolean removed = lockedPlayers.remove(uuid.get());

        findPlayer(targetUuid).ifPresent(player ->
                socketClient.send("player_update", snapshotBuilder.player(player))
        );

        logger.info("Monitor account unlock applied: uuid=" + targetUuid + " removed=" + removed);
        sendActionResult(actionId, "SUCCESS", "Account unlocked");
    }

    private Optional<Player> findPlayer(String targetUuid) {
        Optional<UUID> uuid = parseUuid(targetUuid);
        return uuid.flatMap(proxyServer::getPlayer);
    }

    private Optional<UUID> parseUuid(String targetUuid) {
        if (targetUuid == null || targetUuid.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(targetUuid));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private Optional<UUID> resolveUuid(String target) {
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(target));
        } catch (IllegalArgumentException ignored) {
        }

        return proxyServer.getPlayer(target)
                .map(Player::getUniqueId);
    }

    private void sendActionResult(String actionId, String status, String message) {
        socketClient.send("action_result", Map.of(
                "actionId", actionId == null ? "" : actionId,
                "status", status,
                "message", message
        ));
    }
}