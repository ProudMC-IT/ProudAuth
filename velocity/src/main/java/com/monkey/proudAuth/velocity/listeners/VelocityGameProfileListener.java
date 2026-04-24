package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.session.VelocityPremiumProofStore;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityGameProfileListener {

    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<Boolean> rewriteGameProfileSupplier;
    private final Supplier<Boolean> requireUuidProofSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityPremiumProofStore premiumProofStore;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityGameProfileListener(
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<StorageProvider> storageSupplier,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<Boolean> rewriteGameProfileSupplier,
            Supplier<Boolean> requireUuidProofSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityNetworkGuardService networkGuardService,
            VelocityPremiumProofStore premiumProofStore,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            ProudAuthConsoleLogger logger
    ) {
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.storageSupplier = storageSupplier;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.rewriteGameProfileSupplier = rewriteGameProfileSupplier;
        this.requireUuidProofSupplier = requireUuidProofSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.networkGuardService = networkGuardService;
        this.premiumProofStore = premiumProofStore;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        GameProfile currentProfile = event.getGameProfile();
        GameProfile resolvedProfile = currentProfile;
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getUsername());
        AccountType accountType;
        String ipAddress = "unknown";
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            ipAddress = socketAddress.getAddress().getHostAddress();
        }
        debugEvent(DebugChannel.PROFILE_FLOW, "profile_request",
                "player", event.getUsername(),
                "current_uuid", currentProfile.getId(),
                "offline_uuid", offlineUuid);

        if (!currentProfile.getId().equals(offlineUuid)) {
            boolean trustedIpMatch = hasTrustedIpMatch(event.getUsername(), ipAddress);
            if (requireUuidProofSupplier.get() && !trustedIpMatch) {
                var premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
                if (premiumCheck.premium() && currentProfile.getId().equals(premiumCheck.resolvedUuid())) {
                    accountType = AccountType.PREMIUM;
                    debugEvent(DebugChannel.PROFILE_FLOW, "profile_forwarded_premium_verified",
                            "player", event.getUsername(),
                            "uuid", currentProfile.getId(),
                            "resolved_uuid", premiumCheck.resolvedUuid());
                } else {
                    accountType = AccountType.CRACKED;
                    debugEvent(DebugChannel.PREMIUM_FLOW, "premium_downgraded",
                            "player", event.getUsername(),
                            "reason", "UUID_PROOF_REQUIRED_FORWARDED_MISMATCH",
                            "current_uuid", currentProfile.getId(),
                            "resolved_uuid", premiumCheck.resolvedUuid(),
                            "offline_uuid", offlineUuid);
                }
            } else {
                accountType = AccountType.PREMIUM;
                debugEvent(DebugChannel.PROFILE_FLOW, "profile_forwarded_premium",
                        "player", event.getUsername(),
                        "uuid", currentProfile.getId());
            }
        } else {
            var premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
            debugEvent(DebugChannel.PREMIUM_FLOW, "premium_check",
                    "player", event.getUsername(),
                    "premium", premiumCheck.premium(),
                    "resolved_uuid", premiumCheck.resolvedUuid(),
                    "resolved_name", premiumCheck.resolvedName());
            if (premiumCheck.premium()) {
                boolean currentUuidProvesPremium = !currentProfile.getId().equals(offlineUuid);
                if (requireUuidProofSupplier.get() && !currentUuidProvesPremium) {
                    if (hasTrustedIpMatch(event.getUsername(), ipAddress)) {
                        accountType = AccountType.PREMIUM;
                        debugEvent(DebugChannel.PREMIUM_FLOW, "premium_trusted_ip_override",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "current_uuid", currentProfile.getId(),
                                "offline_uuid", offlineUuid);
                    } else {
                        var cachedProof = premiumProofStore.consume(event.getUsername(), ipAddress);
                        if (cachedProof.isPresent()) {
                            VelocityPremiumProofStore.PremiumProof proof = cachedProof.get();
                            accountType = AccountType.PREMIUM;
                            debugEvent(DebugChannel.PREMIUM_FLOW, "premium_prelogin_proof_accepted",
                                    "player", event.getUsername(),
                                    "ip", ipAddress,
                                    "proof_uuid", proof.resolvedUuid(),
                                    "current_uuid", currentProfile.getId(),
                                    "offline_uuid", offlineUuid);
                        } else {
                            accountType = AccountType.CRACKED;
                            debugEvent(DebugChannel.PREMIUM_FLOW, "premium_downgraded",
                                    "player", event.getUsername(),
                                    "reason", "UUID_PROOF_REQUIRED",
                                    "current_uuid", currentProfile.getId(),
                                    "offline_uuid", offlineUuid);
                        }
                    }
                } else if (rewriteGameProfileSupplier.get()) {
                    resolvedProfile = currentProfile
                            .withId(premiumCheck.resolvedUuid())
                            .withName(premiumCheck.resolvedName())
                            .withProperties(currentProfile.getProperties());
                    event.setGameProfile(resolvedProfile);
                    debugEvent(DebugChannel.PROFILE_FLOW, "profile_rewritten_premium",
                            "player", event.getUsername(),
                            "forwarded_uuid", currentProfile.getId(),
                            "resolved_uuid", resolvedProfile.getId());
                    accountType = AccountType.PREMIUM;
                } else {
                    debugEvent(DebugChannel.PROFILE_FLOW, "profile_rewrite_skipped",
                            "player", event.getUsername(),
                            "forwarded_uuid", currentProfile.getId(),
                            "resolved_premium_uuid", premiumCheck.resolvedUuid());
                    accountType = AccountType.PREMIUM;
                }
            } else {
                accountType = AccountType.CRACKED;
            }
        }

        networkGuardService.recordResolvedProfile(event.getUsername(), ipAddress, accountType);
        debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_publish_start",
                "player", event.getUsername(),
                "resolved_uuid", resolvedProfile.getId(),
                "resolved_name", resolvedProfile.getName(),
                "account_type", accountType,
                "ip", ipAddress,
                "bridge_operational", bridgeServiceSupplier.get().isOperational());

        resolvedPlayerStore.remember(event.getUsername(), resolvedProfile.getId(), resolvedProfile.getName(), accountType);
        debugEvent(DebugChannel.BRIDGE_FLOW, "profile_cache_saved",
                "player", event.getUsername(),
                "resolved_uuid", resolvedProfile.getId(),
                "account_type", accountType);

        bridgeServiceSupplier.get()
                .publish(event.getUsername(), resolvedProfile.getName(), resolvedProfile.getId(), accountType, ipAddress)
                .exceptionally(exception -> {
                    debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_publish_error",
                            "player", event.getUsername(),
                            "error", exception.getMessage());
                    return null;
                })
                .join();
        debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_publish_complete",
                "player", event.getUsername(),
                "resolved_uuid", resolvedProfile.getId());
    }

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), channel, eventName, keyValues);
    }

    private boolean hasTrustedIpMatch(String username, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return false;
        }
        try {
            return storageSupplier.get()
                    .findTrustedIp(username)
                    .join()
                    .map(ipAddress::equalsIgnoreCase)
                    .orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }
}
