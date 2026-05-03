package com.monkey.proudAuth.common.bridge;

import com.monkey.proudAuth.common.model.AccountType;

import java.time.Instant;
import java.util.UUID;

public record ProxyBridgeAssertion(
        String username,
        String resolvedName,
        UUID uuid,
        AccountType accountType,
        String ipAddress,
        BridgeJoinMode joinMode,
        String targetServer,
        String authEntryServer,
        boolean authEntryEnforced,
        String postAuthServer,
        boolean networkAuthenticated,
        Instant issuedAt,
        Instant expiresAt,
        String nonce,
        String signature
) {

    public ProxyBridgeAssertion withSignature(String updatedSignature) {
        return new ProxyBridgeAssertion(
                username,
                resolvedName,
                uuid,
                accountType,
                ipAddress,
                joinMode,
                targetServer,
                authEntryServer,
                authEntryEnforced,
                postAuthServer,
                networkAuthenticated,
                issuedAt,
                expiresAt,
                nonce,
                updatedSignature
        );
    }
}
