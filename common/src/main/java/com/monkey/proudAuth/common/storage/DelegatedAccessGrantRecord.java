package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;
import java.util.UUID;

public record DelegatedAccessGrantRecord(
        UUID ownerUuid,
        String ownerUsername,
        UUID delegateUuid,
        String delegateUsername,
        AccountType delegateAccountType,
        String codeHash,
        Instant createdAt,
        Instant updatedAt,
        boolean active
) {
}
