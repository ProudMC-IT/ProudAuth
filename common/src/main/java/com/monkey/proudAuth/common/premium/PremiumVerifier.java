package com.monkey.proudAuth.common.premium;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface PremiumVerifier {

    CompletableFuture<PremiumCheckResult> verify(String username);

    void reload(ProudAuthSettings settings);

    record PremiumCheckResult(boolean premium, UUID resolvedUuid, String resolvedName) {
    }

    static UUID offlineUuid(String username) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
    }
}
