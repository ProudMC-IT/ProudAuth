package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.model.AccountType;

import java.util.UUID;

public record ResolvedLogin(UUID uuid, String username, AccountType accountType, String ipAddress) {
}
