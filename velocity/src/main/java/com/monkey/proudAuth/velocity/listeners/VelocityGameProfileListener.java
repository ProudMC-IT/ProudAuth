package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.monkey.proudAuth.velocity.session.VelocityWhitelistEnforcementStore;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.util.GameProfile;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityGameProfileListener {

    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityGameProfileListener(
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityNetworkGuardService networkGuardService,
            VelocityWhitelistEnforcementStore whitelistEnforcementStore,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            ProudAuthConsoleLogger logger
    ) {
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.networkGuardService = networkGuardService;
        this.whitelistEnforcementStore = whitelistEnforcementStore;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        GameProfile currentProfile = event.getGameProfile();
        String ipAddress = resolveIp(event.getConnection());
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getUsername());
        boolean premiumRequiredByWhitelist = whitelistEnforcementStore.requiresPremium(event.getUsername(), ipAddress);

        PremiumVerifier.PremiumCheckResult premiumCheck =
                premiumVerifierSupplier.get().verify(event.getUsername()).join();

        if (premiumCheck.premium()) {
            boolean uuidAlreadyCorrect = !currentProfile.getId().equals(offlineUuid);
            boolean premiumVerified = event.isOnlineMode() || uuidAlreadyCorrect;

            resolvedPlayerStore.remember(
                    event.getUsername(),
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName(),
                    premiumVerified ? AccountType.PREMIUM : AccountType.CRACKED,
                    true,
                    premiumVerified
            );

            if (premiumVerified) {
                publishResolvedProfile(
                        event.getUsername(),
                        ipAddress,
                        premiumCheck.resolvedUuid(),
                        premiumCheck.resolvedName(),
                        AccountType.PREMIUM
                );
                if (premiumRequiredByWhitelist) {
                    whitelistEnforcementStore.forget(event.getUsername(), ipAddress);
                }
            } else {
                debugEvent(DebugChannel.PREMIUM_FLOW, "premium_pending_key_challenge",
                        "player", event.getUsername(),
                        "ip", ipAddress,
                        "event_online_mode", event.isOnlineMode(),
                        "current_profile_uuid", currentProfile.getId(),
                        "offline_uuid", offlineUuid,
                        "premium_uuid", premiumCheck.resolvedUuid());
            }

            debugEvent(DebugChannel.PROFILE_FLOW, "profile_resolved",
                    "player", event.getUsername(),
                    "current_profile_uuid", currentProfile.getId(),
                    "resolved_uuid", premiumCheck.resolvedUuid(),
                    "account_type", premiumVerified ? AccountType.PREMIUM : AccountType.CRACKED,
                    "premium_check", true,
                    "premium_verified", premiumVerified,
                    "ip", ipAddress);
            return;
        }

        resolvedPlayerStore.remember(
                event.getUsername(),
                currentProfile.getId(),
                currentProfile.getName(),
                AccountType.CRACKED,
                false,
                false
        );

        if (premiumRequiredByWhitelist) {
            debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_pending_disconnect",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "current_uuid", currentProfile.getId());
        }

        publishResolvedProfile(
                event.getUsername(),
                ipAddress,
                currentProfile.getId(),
                currentProfile.getName(),
                AccountType.CRACKED
        );

        debugEvent(DebugChannel.PROFILE_FLOW, "profile_resolved",
                "player", event.getUsername(),
                "current_profile_uuid", currentProfile.getId(),
                "resolved_uuid", currentProfile.getId(),
                "account_type", AccountType.CRACKED,
                "premium_check", false,
                "premium_verified", false,
                "ip", ipAddress);
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        String ipAddress = resolveIp(event.getPlayer());
        boolean premiumRequiredByWhitelist = whitelistEnforcementStore.requiresPremium(username, ipAddress);
        VelocityResolvedPlayerStore.ResolvedPlayer resolvedPlayer = resolvedPlayerStore.find(username).orElse(null);

        if (resolvedPlayer == null) {
            if (premiumRequiredByWhitelist) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_missing_resolution",
                        "player", username,
                        "ip", ipAddress);
                event.setResult(ResultedEvent.ComponentResult.denied(
                        langSupplier.get().message("kick-whitelist-premium-required")));
                whitelistEnforcementStore.forget(username, ipAddress);
            }
            return;
        }

        if (!resolvedPlayer.premiumNameDetected()) {
            if (!premiumRequiredByWhitelist) {
                return;
            }

            debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_denied",
                    "player", username,
                    "ip", ipAddress,
                    "resolved_account_type", resolvedPlayer.accountType(),
                    "premium_verified", false);
            event.setResult(ResultedEvent.ComponentResult.denied(
                    langSupplier.get().message("kick-whitelist-premium-required")));
            whitelistEnforcementStore.forget(username, ipAddress);
            return;
        }

        if (resolvedPlayer.premiumVerified()) {
            if (premiumRequiredByWhitelist) {
                whitelistEnforcementStore.forget(username, ipAddress);
            }
            return;
        }

        PremiumProofDecision proofDecision = evaluatePremiumProof(event, resolvedPlayer.accountUuid());
        if (proofDecision.verified()) {
            resolvedPlayerStore.remember(
                    username,
                    resolvedPlayer.accountUuid(),
                    resolvedPlayer.accountName(),
                    AccountType.PREMIUM,
                    true,
                    true
            );
            publishResolvedProfile(
                    username,
                    ipAddress,
                    resolvedPlayer.accountUuid(),
                    resolvedPlayer.accountName(),
                    AccountType.PREMIUM
            );
            debugEvent(DebugChannel.PREMIUM_FLOW, "premium_key_challenge_verified",
                    "player", username,
                    "ip", ipAddress,
                    "method", proofDecision.method(),
                    "signature_holder", proofDecision.signatureHolder(),
                    "signature_valid_reported", proofDecision.signatureValidReported());
            if (premiumRequiredByWhitelist) {
                whitelistEnforcementStore.forget(username, ipAddress);
            }
            return;
        }

        debugEvent(DebugChannel.PREMIUM_FLOW, "premium_key_challenge_denied",
                "player", username,
                "ip", ipAddress,
                "method", proofDecision.method(),
                "reason", proofDecision.reason(),
                "signature_holder", proofDecision.signatureHolder(),
                "signature_valid_reported", proofDecision.signatureValidReported(),
                "expected_uuid", resolvedPlayer.accountUuid());

        if (premiumRequiredByWhitelist) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    langSupplier.get().message("kick-whitelist-premium-required")));
        } else if (proofDecision.reason() == PremiumProofFailure.HOLDER_MISMATCH) {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    langSupplier.get().message("kick-premium-impersonation")));
        } else {
            event.setResult(ResultedEvent.ComponentResult.denied(
                    langSupplier.get().message("kick-premium-proof-required")));
        }
        whitelistEnforcementStore.forget(username, ipAddress);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        whitelistEnforcementStore.forget(event.getPlayer().getUsername(), resolveIp(event.getPlayer()));
    }

    private void publishResolvedProfile(
            String username,
            String ipAddress,
            UUID accountUuid,
            String accountName,
            AccountType accountType
    ) {
        networkGuardService.recordResolvedProfile(username, ipAddress, accountType);

        bridgeServiceSupplier.get()
                .publish(
                        username,
                        accountName,
                        accountUuid,
                        accountType,
                        ipAddress
                )
                .exceptionally(exception -> {
                    debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_publish_error",
                            "player", username,
                            "error", exception.getMessage());
                    return null;
                })
                .join();
    }

    private PremiumProofDecision evaluatePremiumProof(LoginEvent event, UUID expectedPremiumUuid) {
        Player player = event.getPlayer();
        if (player.isOnlineMode() || event.getServerIdHash() != null) {
            return PremiumProofDecision.verified(
                    "proxy_online_mode",
                    player.getUniqueId(),
                    true
            );
        }

        IdentifiedKey identifiedKey = player.getIdentifiedKey();
        if (identifiedKey == null) {
            return PremiumProofDecision.denied("identified_key", PremiumProofFailure.MISSING_KEY, null, false);
        }

        UUID signatureHolder = identifiedKey.getSignatureHolder();
        if (signatureHolder == null) {
            return PremiumProofDecision.denied(
                    "identified_key",
                    PremiumProofFailure.MISSING_HOLDER,
                    null,
                    identifiedKey.isSignatureValid()
            );
        }
        if (identifiedKey.hasExpired()) {
            return PremiumProofDecision.denied(
                    "identified_key",
                    PremiumProofFailure.EXPIRED_KEY,
                    signatureHolder,
                    identifiedKey.isSignatureValid()
            );
        }
        if (!expectedPremiumUuid.equals(signatureHolder)) {
            return PremiumProofDecision.denied(
                    "identified_key",
                    PremiumProofFailure.HOLDER_MISMATCH,
                    signatureHolder,
                    identifiedKey.isSignatureValid()
            );
        }
        return PremiumProofDecision.verified(
                "identified_key",
                signatureHolder,
                identifiedKey.isSignatureValid()
        );
    }

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), channel, eventName, keyValues);
    }

    private String resolveIp(InboundConnection connection) {
        if (connection.getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private enum PremiumProofFailure {
        MISSING_KEY,
        MISSING_HOLDER,
        EXPIRED_KEY,
        HOLDER_MISMATCH
    }

    private record PremiumProofDecision(
            boolean verified,
            String method,
            PremiumProofFailure reason,
            UUID signatureHolder,
            boolean signatureValidReported
    ) {
        private static PremiumProofDecision verified(String method, UUID signatureHolder, boolean signatureValidReported) {
            return new PremiumProofDecision(true, method, null, signatureHolder, signatureValidReported);
        }

        private static PremiumProofDecision denied(
                String method,
                PremiumProofFailure reason,
                UUID signatureHolder,
                boolean signatureValidReported
        ) {
            return new PremiumProofDecision(false, method, reason, signatureHolder, signatureValidReported);
        }
    }
}
