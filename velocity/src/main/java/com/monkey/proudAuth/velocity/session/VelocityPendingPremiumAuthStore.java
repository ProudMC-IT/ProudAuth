package com.monkey.proudAuth.velocity.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPendingPremiumAuthStore {

    private final Map<String, String> usernamesByConnectionKey = new ConcurrentHashMap<>();

    public void remember(String connectionKey, String username) {
        if (connectionKey == null || username == null) {
            return;
        }
        usernamesByConnectionKey.put(connectionKey, username);
    }

    public Optional<String> consume(String connectionKey) {
        if (connectionKey == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(usernamesByConnectionKey.remove(connectionKey));
    }

    public boolean contains(String connectionKey) {
        if (connectionKey == null) {
            return false;
        }
        return usernamesByConnectionKey.containsKey(connectionKey);
    }

    public void forget(String connectionKey) {
        if (connectionKey == null) {
            return;
        }
        usernamesByConnectionKey.remove(connectionKey);
    }
}
