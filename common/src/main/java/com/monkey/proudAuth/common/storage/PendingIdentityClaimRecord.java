package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;

public record PendingIdentityClaimRecord(
        String username,
        AccountType claimType,
        String ipAddress,
        Instant createdAt,
        Instant expiresAt
) {
}
