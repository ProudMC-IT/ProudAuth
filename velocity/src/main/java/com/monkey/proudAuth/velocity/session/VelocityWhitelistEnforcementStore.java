package com.monkey.proudAuth.velocity.session;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityWhitelistEnforcementStore {

    private final Map<String, String> enforcedPremiumJoins = new ConcurrentHashMap<>();

    public void remember(String username, String ipAddress) {
        if (username == null || ipAddress == null) {
            return;
        }
        String key = key(username, ipAddress);
        enforcedPremiumJoins.put(key, key);
    }

    public boolean requiresPremium(String username, String ipAddress) {
        if (username == null || ipAddress == null) {
            return false;
        }
        return enforcedPremiumJoins.containsKey(key(username, ipAddress));
    }

    public void forget(String username, String ipAddress) {
        if (username == null || ipAddress == null) {
            return;
        }
        enforcedPremiumJoins.remove(key(username, ipAddress));
    }

    private static String key(String username, String ipAddress) {
        return username.toLowerCase(Locale.ROOT) + "|" + ipAddress.toLowerCase(Locale.ROOT);
    }
}
