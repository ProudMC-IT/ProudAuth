package com.monkey.proudAuth.velocity.session;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityPremiumProofStore {

    private static final long PROOF_TTL_MILLIS = 30_000L;

    private final Map<String, PremiumProof> proofs = new ConcurrentHashMap<>();

    public void remember(String username, String ipAddress, UUID resolvedUuid, String resolvedName) {
        if (username == null || ipAddress == null || resolvedUuid == null || resolvedName == null) {
            return;
        }
        long expiresAt = System.currentTimeMillis() + PROOF_TTL_MILLIS;
        proofs.put(key(username, ipAddress), new PremiumProof(resolvedUuid, resolvedName, expiresAt));
    }

    public Optional<PremiumProof> consume(String username, String ipAddress) {
        if (username == null || ipAddress == null) {
            return Optional.empty();
        }
        PremiumProof proof = proofs.remove(key(username, ipAddress));
        if (proof == null || proof.expiresAtMillis() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(proof);
    }

    private static String key(String username, String ipAddress) {
        return username.toLowerCase(Locale.ROOT) + "|" + ipAddress.toLowerCase(Locale.ROOT);
    }

    public record PremiumProof(UUID resolvedUuid, String resolvedName, long expiresAtMillis) {
    }
}
