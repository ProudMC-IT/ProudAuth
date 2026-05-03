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
            boolean premiumEnforced
    ) {
        resolvedPlayers.compute(key(username), (ignored, current) -> new ResolvedPlayer(
                accountUuid,
                accountName,
                accountType,
                premiumNameDetected,
                premiumVerified,
                premiumEnforced,
                current != null && current.networkAuthenticated(),
                current == null ? "" : current.authEntryServer()
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
            boolean networkAuthenticated,
            String authEntryServer
    ) {
        public ResolvedPlayer withNetworkAuthenticated(boolean authenticated) {
            return new ResolvedPlayer(
                    accountUuid,
                    accountName,
                    accountType,
                    premiumNameDetected,
                    premiumVerified,
                    premiumEnforced,
                    authenticated,
                    authEntryServer
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
                    networkAuthenticated,
                    updatedAuthEntryServer == null ? "" : updatedAuthEntryServer
            );
        }
    }
}
