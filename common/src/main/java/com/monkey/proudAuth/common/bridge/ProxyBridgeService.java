package com.monkey.proudAuth.common.bridge;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.BridgeAssertionStorage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class ProxyBridgeService {

    private final BridgeAssertionStorage storage;
    private volatile ProudAuthSettings settings;

    public ProxyBridgeService(BridgeAssertionStorage storage, ProudAuthSettings settings) {
        this.storage = storage;
        this.settings = settings;
    }

    public CompletableFuture<Void> publish(
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
            boolean networkAuthenticated
    ) {
        if (!isOperational()) {
            return CompletableFuture.completedFuture(null);
        }

        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plusSeconds(Math.max(1L, settings.bridge().assertionTtlSeconds())).truncatedTo(ChronoUnit.SECONDS);
        ProxyBridgeAssertion unsignedAssertion = new ProxyBridgeAssertion(
                canonicalUsername(username),
                resolvedName,
                uuid,
                accountType,
                ipAddress,
                joinMode,
                normalizeServerName(targetServer),
                normalizeServerName(authEntryServer),
                authEntryEnforced,
                normalizeServerName(postAuthServer),
                networkAuthenticated,
                issuedAt,
                expiresAt,
                UUID.randomUUID().toString().replace("-", ""),
                ""
        );
        ProxyBridgeAssertion signedAssertion = unsignedAssertion.withSignature(sign(unsignedAssertion, settings.bridge().sharedSecret()));
        return storage.saveProxyAssertion(signedAssertion);
    }

    public CompletableFuture<VerificationResult> consumeAndVerify(
            String username,
            UUID currentUuid,
            String ipAddress,
            String currentServerId
    ) {
        if (!settings.bridge().enabled()) {
            return CompletableFuture.completedFuture(new VerificationResult(VerificationStatus.DISABLED, Optional.empty()));
        }
        if (settings.bridge().transport() != ProudAuthSettings.BridgeTransport.MYSQL) {
            return CompletableFuture.completedFuture(new VerificationResult(VerificationStatus.UNSUPPORTED_TRANSPORT, Optional.empty()));
        }
        if (settings.bridge().sharedSecret() == null || settings.bridge().sharedSecret().isBlank()) {
            return CompletableFuture.completedFuture(new VerificationResult(VerificationStatus.MISCONFIGURED, Optional.empty()));
        }

        String canonicalUsername = canonicalUsername(username);
        return storage.findLatestProxyAssertion(canonicalUsername, ipAddress)
                .thenCompose(optionalAssertion -> {
                    if (optionalAssertion.isEmpty()) {
                        return CompletableFuture.completedFuture(new VerificationResult(VerificationStatus.MISSING, Optional.empty()));
                    }

                    ProxyBridgeAssertion assertion = optionalAssertion.get();
                    return storage.deleteProxyAssertion(assertion.nonce())
                            .exceptionally(exception -> null)
                            .thenApply(ignored -> validateAssertion(
                                    username,
                                    canonicalUsername,
                                    currentUuid,
                                    ipAddress,
                                    normalizeServerName(currentServerId),
                                    assertion
                            ));
                });
    }

    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    public boolean isOperational() {
        return settings.bridge().enabled()
                && settings.bridge().transport() == ProudAuthSettings.BridgeTransport.MYSQL
                && settings.bridge().sharedSecret() != null
                && !settings.bridge().sharedSecret().isBlank();
    }

    private VerificationResult validateAssertion(
            String originalUsername,
            String canonicalUsername,
            UUID currentUuid,
            String ipAddress,
            String currentServerId,
            ProxyBridgeAssertion assertion
    ) {
        Instant now = Instant.now();
        if (!assertion.username().equals(canonicalUsername)) {
            return new VerificationResult(VerificationStatus.INVALID_USERNAME, Optional.empty());
        }
        if (!assertion.ipAddress().equals(ipAddress)) {
            return new VerificationResult(VerificationStatus.INVALID_IP, Optional.empty());
        }
        if (!assertion.targetServer().isBlank()
                && currentServerId != null
                && !currentServerId.isBlank()
                && !assertion.targetServer().equalsIgnoreCase(currentServerId)) {
            return new VerificationResult(VerificationStatus.INVALID_TARGET_SERVER, Optional.empty());
        }
        if (!assertion.expiresAt().isAfter(now) || assertion.issuedAt().isAfter(assertion.expiresAt())) {
            return new VerificationResult(VerificationStatus.EXPIRED, Optional.empty());
        }

        UUID offlineUuid = PremiumVerifier.offlineUuid(originalUsername);
        if (!currentUuid.equals(assertion.uuid()) && !currentUuid.equals(offlineUuid)) {
            return new VerificationResult(VerificationStatus.INVALID_UUID, Optional.empty());
        }

        String expectedSignature = sign(assertion.withSignature(""), settings.bridge().sharedSecret());
        if (!expectedSignature.equalsIgnoreCase(assertion.signature())) {
            return new VerificationResult(VerificationStatus.INVALID_SIGNATURE, Optional.empty());
        }

        return new VerificationResult(VerificationStatus.ACCEPTED, Optional.of(assertion));
    }

    private static String canonicalUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private static String sign(ProxyBridgeAssertion assertion, String sharedSecret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload(assertion).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Impossibile firmare l'assertion del bridge ProudAuth.", exception);
        }
    }

    private static String payload(ProxyBridgeAssertion assertion) {
        return String.join("|",
                assertion.username(),
                assertion.resolvedName(),
                assertion.uuid().toString(),
                assertion.accountType().name(),
                assertion.ipAddress(),
                assertion.joinMode().name(),
                assertion.targetServer(),
                assertion.authEntryServer(),
                Boolean.toString(assertion.authEntryEnforced()),
                assertion.postAuthServer(),
                Boolean.toString(assertion.networkAuthenticated()),
                Long.toString(assertion.issuedAt().toEpochMilli()),
                Long.toString(assertion.expiresAt().toEpochMilli()),
                assertion.nonce()
        );
    }

    private static String normalizeServerName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    public enum VerificationStatus {
        DISABLED,
        UNSUPPORTED_TRANSPORT,
        MISCONFIGURED,
        MISSING,
        EXPIRED,
        INVALID_USERNAME,
        INVALID_IP,
        INVALID_TARGET_SERVER,
        INVALID_UUID,
        INVALID_SIGNATURE,
        ACCEPTED
    }

    public record VerificationResult(VerificationStatus status, Optional<ProxyBridgeAssertion> assertion) {
        public boolean accepted() {
            return status == VerificationStatus.ACCEPTED && assertion.isPresent();
        }
    }
}
