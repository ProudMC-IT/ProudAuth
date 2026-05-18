package com.monkey.proudAuth.velocity.session;

import com.monkey.proudAuth.common.model.AccountType;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityResolvedPlayerStore {

    private final Map<String, ResolvedPlayer> resolvedPlayers = new ConcurrentHashMap<>();

    public void remember(
            String username,
            UUID accountUuid,
            String accountName,
            AccountType accountType,
            boolean premiumNameDetected,
            boolean premiumVerified,
            boolean premiumEnforced,
            String protocolVersion,
            boolean legacyClient
    ) {
        resolvedPlayers.compute(key(username), (ignored, current) -> new ResolvedPlayer(
                accountUuid,
                accountName,
                accountType,
                premiumNameDetected,
                premiumVerified,
                premiumEnforced,
                protocolVersion == null ? "" : protocolVersion,
                legacyClient,
                current != null && current.networkAuthenticated(),
                current == null ? "" : current.authEntryServer(),
                current == null ? "" : current.pendingNoticeKey(),
                current == null ? "" : current.pendingNoticeTargetServer(),
                current == null ? "" : current.allowedAuthEntryTargetServer()
        ));
    }

    public Optional<ResolvedPlayer> find(String username) {
        return Optional.ofNullable(resolvedPlayers.get(key(username)));
    }

    public void rememberAuthEntryServer(String username, String authEntryServer) {
        resolvedPlayers.computeIfPresent(key(username), (ignored, current) -> current.withAuthEntryServer(authEntryServer));
    }

    public void markNetworkAuthenticated(String username, boolean authenticated) {
        resolvedPlayers.computeIfPresent(key(username), (ignored, current) -> current.withNetworkAuthenticated(authenticated));
    }

    public void rememberPendingNotice(String username, String messageKey, String targetServer) {
        resolvedPlayers.computeIfPresent(key(username),
                (ignored, current) -> current.withPendingNotice(messageKey, targetServer));
    }

    public void rememberAllowedAuthEntry(String username, String targetServer) {
        resolvedPlayers.computeIfPresent(key(username),
                (ignored, current) -> current.withAllowedAuthEntryTargetServer(targetServer));
    }

    public Optional<String> consumePendingNotice(String username, String currentServer) {
        String normalizedServer = currentServer == null ? "" : currentServer;
        String[] consumedMessage = new String[1];
        resolvedPlayers.computeIfPresent(key(username), (ignored, current) -> {
            if (current.pendingNoticeKey().isBlank()
                    || current.pendingNoticeTargetServer().isBlank()
                    || !current.pendingNoticeTargetServer().equalsIgnoreCase(normalizedServer)) {
                return current;
            }
            consumedMessage[0] = current.pendingNoticeKey();
            return current.withPendingNotice("", "");
        });
        return Optional.ofNullable(consumedMessage[0]).filter(messageKey -> !messageKey.isBlank());
    }

    public boolean consumeAllowedAuthEntry(String username, String targetServer) {
        String normalizedServer = targetServer == null ? "" : targetServer;
        boolean[] consumed = new boolean[1];
        resolvedPlayers.computeIfPresent(key(username), (ignored, current) -> {
            if (current.allowedAuthEntryTargetServer().isBlank()
                    || !current.allowedAuthEntryTargetServer().equalsIgnoreCase(normalizedServer)) {
                return current;
            }
            consumed[0] = true;
            return current.withAllowedAuthEntryTargetServer("");
        });
        return consumed[0];
    }

    public void forget(String username) {
        resolvedPlayers.remove(key(username));
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public record ResolvedPlayer(
            UUID accountUuid,
            String accountName,
            AccountType accountType,
            boolean premiumNameDetected,
            boolean premiumVerified,
            boolean premiumEnforced,
            String protocolVersion,
            boolean legacyClient,
            boolean networkAuthenticated,
            String authEntryServer,
            String pendingNoticeKey,
            String pendingNoticeTargetServer,
            String allowedAuthEntryTargetServer
    ) {
        public ResolvedPlayer withNetworkAuthenticated(boolean authenticated) {
            return new ResolvedPlayer(
                    accountUuid,
                    accountName,
                    accountType,
                    premiumNameDetected,
                    premiumVerified,
                    premiumEnforced,
                    protocolVersion,
                    legacyClient,
                    authenticated,
                    authEntryServer,
                    pendingNoticeKey,
                    pendingNoticeTargetServer,
                    allowedAuthEntryTargetServer
            );
        }

        public ResolvedPlayer withAuthEntryServer(String updatedAuthEntryServer) {
            return new ResolvedPlayer(
                    accountUuid,
                    accountName,
                    accountType,
                    premiumNameDetected,
                    premiumVerified,
                    premiumEnforced,
                    protocolVersion,
                    legacyClient,
                    networkAuthenticated,
                    updatedAuthEntryServer == null ? "" : updatedAuthEntryServer,
                    pendingNoticeKey,
                    pendingNoticeTargetServer,
                    allowedAuthEntryTargetServer
            );
        }

        public ResolvedPlayer withPendingNotice(String updatedMessageKey, String updatedTargetServer) {
            return new ResolvedPlayer(
                    accountUuid,
                    accountName,
                    accountType,
                    premiumNameDetected,
                    premiumVerified,
                    premiumEnforced,
                    protocolVersion,
                    legacyClient,
                    networkAuthenticated,
                    authEntryServer,
                    updatedMessageKey == null ? "" : updatedMessageKey,
                    updatedTargetServer == null ? "" : updatedTargetServer,
                    allowedAuthEntryTargetServer
            );
        }

        public ResolvedPlayer withAllowedAuthEntryTargetServer(String updatedAllowedAuthEntryTargetServer) {
            return new ResolvedPlayer(
                    accountUuid,
                    accountName,
                    accountType,
                    premiumNameDetected,
                    premiumVerified,
                    premiumEnforced,
                    protocolVersion,
                    legacyClient,
                    networkAuthenticated,
                    authEntryServer,
                    pendingNoticeKey,
                    pendingNoticeTargetServer,
                    updatedAllowedAuthEntryTargetServer == null ? "" : updatedAllowedAuthEntryTargetServer
            );
        }
    }
}
