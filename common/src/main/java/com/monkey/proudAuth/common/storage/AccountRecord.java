package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record AccountRecord(
        UUID uuid,
        String username,
        @Nullable String passwordHash,
        AccountType accountType,
        @Nullable String email,
        @Nullable String totpSecret,
        boolean totpFlowAlways,
        Instant registeredAt,
        @Nullable Instant lastLoginAt,
        @Nullable String lastIp
) {
}
