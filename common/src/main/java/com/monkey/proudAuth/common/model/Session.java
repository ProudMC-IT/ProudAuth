package com.monkey.proudAuth.common.model;

import java.time.Instant;
import java.util.UUID;

public record Session(String token, String ip, UUID uuid, AccountType accountType, Instant expiresAt) {

    public boolean isExpired() {
        return expiresAt.isBefore(Instant.now());
    }
}
