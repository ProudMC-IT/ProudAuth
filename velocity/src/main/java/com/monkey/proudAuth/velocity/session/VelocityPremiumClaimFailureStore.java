package com.monkey.proudAuth.velocity.session;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPremiumClaimFailureStore {

    private final Map<String, Boolean> failedClaims = new ConcurrentHashMap<>();

    public void remember(String username, String ipAddress) {
        if (username == null || ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return;
        }
        failedClaims.put(key(username, ipAddress), Boolean.TRUE);
    }

    public boolean consume(String username, String ipAddress) {
        if (username == null || ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return false;
        }
        return failedClaims.remove(key(username, ipAddress)) != null;
    }

    private String key(String username, String ipAddress) {
        return username.toLowerCase(Locale.ROOT) + "|" + ipAddress.toLowerCase(Locale.ROOT);
    }
}
