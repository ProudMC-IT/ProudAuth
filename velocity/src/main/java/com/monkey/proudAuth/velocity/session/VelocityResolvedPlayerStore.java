package com.monkey.proudAuth.velocity.session;

import com.monkey.proudAuth.common.model.AccountType;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityResolvedPlayerStore {

    private final Map<String, ResolvedPlayer> resolvedPlayers = new ConcurrentHashMap<>();

    public void remember(String username, UUID resolvedUuid, String resolvedName, AccountType accountType) {
        resolvedPlayers.put(key(username), new ResolvedPlayer(resolvedUuid, resolvedName, accountType));
    }

    public Optional<ResolvedPlayer> find(String username) {
        return Optional.ofNullable(resolvedPlayers.get(key(username)));
    }

    public void forget(String username) {
        resolvedPlayers.remove(key(username));
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public record ResolvedPlayer(UUID resolvedUuid, String resolvedName, AccountType accountType) {
    }
}
