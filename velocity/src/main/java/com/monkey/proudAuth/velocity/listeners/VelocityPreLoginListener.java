package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.config.VelocityPluginSettings;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.session.VelocityPremiumProofStore;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

public final class VelocityPreLoginListener {

    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<VelocityPluginSettings.Premium> premiumSettingsSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final VelocityBackendJoinProbeService backendJoinProbeService;
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityPremiumProofStore premiumProofStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityPreLoginListener(
            Supplier<StorageProvider> storageSupplier,
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<VelocityPluginSettings.Premium> premiumSettingsSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityBackendJoinProbeService backendJoinProbeService,
            VelocityNetworkGuardService networkGuardService,
            VelocityPremiumProofStore premiumProofStore,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.premiumSettingsSupplier = premiumSettingsSupplier;
        this.langSupplier = langSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.backendJoinProbeService = backendJoinProbeService;
        this.networkGuardService = networkGuardService;
        this.premiumProofStore = premiumProofStore;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> handlePreLogin(event));
    }

    private void handlePreLogin(PreLoginEvent event) {
        String ipAddress = "unknown";
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            ipAddress = socketAddress.getAddress().getHostAddress();
        }
        var currentUuid = event.getUniqueId();
        debugEvent("prelogin_start",
                "player", event.getUsername(),
                "ip", ipAddress,
                "uuid", currentUuid);

        VelocityLang lang = langSupplier.get();
        var activeBan = storageSupplier.get().findActiveBan(ipAddress).join();
        debugEvent("prelogin_ip_ban_check",
                "player", event.getUsername(),
                "ip", ipAddress,
                "banned", activeBan.isPresent());
        if (activeBan.isPresent()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-ip-banned")));
            return;
        }

        var trustedIp = storageSupplier.get().findTrustedIp(event.getUsername()).join();
        if (trustedIp.isPresent() && !trustedIp.get().equalsIgnoreCase(ipAddress)) {
            debugEvent("prelogin_trusted_ip_mismatch",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "trusted_ip", trustedIp.get());
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-trusted-ip-mismatch")));
            return;
        }

        PremiumDecision premiumDecision = evaluatePremiumIdentity(event.getUsername(), ipAddress, currentUuid);
        if (premiumDecision.denied()) {
            debugEvent("prelogin_premium_impersonation_denied",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "uuid", currentUuid);
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-premium-impersonation")));
            return;
        }
        if (premiumDecision.proof().isPresent()) {
            VerifiedPremiumProof proof = premiumDecision.proof().get();
            premiumProofStore.remember(event.getUsername(), ipAddress, proof.resolvedUuid(), proof.resolvedName());
            debugEvent("prelogin_premium_proof_cached",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "resolved_uuid", proof.resolvedUuid());
        }

        VelocityNetworkGuardService.GuardDecision guardDecision = networkGuardService.evaluate(event.getUsername(), ipAddress);
        debugEvent("prelogin_network_guard",
                "player", event.getUsername(),
                "ip", ipAddress,
                "allowed", guardDecision.allowed(),
                "reason", guardDecision.reason());
        if (!guardDecision.allowed()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-suspicious-network")));
            return;
        }

        VelocityBackendJoinProbeService.ProbeStatus probeStatus = backendJoinProbeService.probe(event.getUsername(), ipAddress);
        debugEvent("prelogin_backend_probe",
                "player", event.getUsername(),
                "ip", ipAddress,
                "status", probeStatus);
        if (probeStatus == VelocityBackendJoinProbeService.ProbeStatus.TIMEOUT
                || probeStatus == VelocityBackendJoinProbeService.ProbeStatus.ERROR) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-backend-unavailable")));
        }
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.IP_BAN_FLOW, eventName, keyValues);
    }

    private PremiumDecision evaluatePremiumIdentity(String username, String ipAddress, java.util.UUID currentUuid) {
        VelocityPluginSettings.Premium premiumSettings = premiumSettingsSupplier.get();
        if (!premiumSettings.enabled() || !premiumSettings.requireUuidProof()) {
            return PremiumDecision.allow();
        }
        if (hasTrustedIpMatch(username, ipAddress)) {
            return PremiumDecision.allow();
        }
        try {
            PremiumVerifier.PremiumCheckResult premiumCheck = premiumVerifierSupplier.get().verify(username).join();
            if (!premiumCheck.premium()) {
                return PremiumDecision.allow();
            }
            if (currentUuid == null) {
                return PremiumDecision.deny();
            }
            if (currentUuid.equals(premiumCheck.resolvedUuid())) {
                return PremiumDecision.allowWithProof(new VerifiedPremiumProof(
                        premiumCheck.resolvedUuid(),
                        premiumCheck.resolvedName()
                ));
            }
            return PremiumDecision.deny();
        } catch (Exception exception) {
            debugEvent("prelogin_premium_impersonation_check_error",
                    "player", username,
                    "ip", ipAddress,
                    "error", exception.getMessage());
            return PremiumDecision.allow();
        }
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

    private record PremiumDecision(boolean denied, java.util.Optional<VerifiedPremiumProof> proof) {
        private static PremiumDecision allow() {
            return new PremiumDecision(false, java.util.Optional.empty());
        }

        private static PremiumDecision allowWithProof(VerifiedPremiumProof proof) {
            return new PremiumDecision(false, java.util.Optional.of(proof));
        }

        private static PremiumDecision deny() {
            return new PremiumDecision(true, java.util.Optional.empty());
        }
    }

    private record VerifiedPremiumProof(java.util.UUID resolvedUuid, String resolvedName) {
    }
}
