package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;

public record IdentityClaimRecord(
        String username,
        AccountType claimType,
        String claimedIp,
        Instant claimedAt,
        Instant updatedAt
) {
}
