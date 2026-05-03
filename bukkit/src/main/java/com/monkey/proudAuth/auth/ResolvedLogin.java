package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.model.AccountType;

import java.util.UUID;

public record ResolvedLogin(
        UUID uuid,
        String username,
        AccountType accountType,
        String ipAddress,
        BridgeJoinMode joinMode,
        String authEntryServer,
        boolean authEntryEnforced,
        String postAuthServer,
        boolean networkAuthenticated
) {
    public static ResolvedLogin standalone(
            UUID uuid,
            String username,
            AccountType accountType,
            String ipAddress,
            String authEntryServer
    ) {
        return new ResolvedLogin(
                uuid,
                username,
                accountType,
                ipAddress,
                BridgeJoinMode.AUTH_ENTRY,
                authEntryServer == null ? "" : authEntryServer,
                false,
                "",
                false
        );
    }
}
