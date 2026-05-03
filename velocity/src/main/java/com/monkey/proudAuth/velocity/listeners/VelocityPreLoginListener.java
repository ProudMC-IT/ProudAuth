package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.bridge.VelocityBackendJoinProbeService;
import com.monkey.proudAuth.velocity.compat.ProudXPreLoginResults;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.instrumentation.VelocityOnlineModeDisconnectAdvice;
import com.monkey.proudAuth.velocity.security.VelocityNetworkGuardService;
import com.monkey.proudAuth.velocity.session.VelocityPendingPremiumAuthStore;
import com.monkey.proudAuth.velocity.session.VelocityPremiumClaimFailureStore;
import com.monkey.proudAuth.velocity.session.VelocityWhitelistEnforcementStore;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.network.ProtocolVersion;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityPreLoginListener {

    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final IdentityClaimService identityClaimService;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings> settingsSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final Supplier<ProudAuthNetworkConfig.Routing> routingSupplier;
    private final VelocityBackendJoinProbeService backendJoinProbeService;
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore;
    private final VelocityPendingPremiumAuthStore pendingPremiumAuthStore;
    private final VelocityPremiumClaimFailureStore premiumClaimFailureStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityPreLoginListener(
            Supplier<StorageProvider> storageSupplier,
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            IdentityClaimService identityClaimService,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings> settingsSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            Supplier<ProudAuthNetworkConfig.Routing> routingSupplier,
            VelocityBackendJoinProbeService backendJoinProbeService,
            VelocityNetworkGuardService networkGuardService,
            VelocityWhitelistEnforcementStore whitelistEnforcementStore,
            VelocityPendingPremiumAuthStore pendingPremiumAuthStore,
            VelocityPremiumClaimFailureStore premiumClaimFailureStore,
            ProudAuthConsoleLogger logger
    ) {
        this.storageSupplier = storageSupplier;
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.identityClaimService = identityClaimService;
        this.langSupplier = langSupplier;
        this.settingsSupplier = settingsSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.routingSupplier = routingSupplier;
        this.backendJoinProbeService = backendJoinProbeService;
        this.networkGuardService = networkGuardService;
        this.whitelistEnforcementStore = whitelistEnforcementStore;
        this.pendingPremiumAuthStore = pendingPremiumAuthStore;
        this.premiumClaimFailureStore = premiumClaimFailureStore;
        this.logger = logger;
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public EventTask onPreLogin(PreLoginEvent event) {
        return EventTask.async(() -> handlePreLogin(event));
    }

    private void handlePreLogin(PreLoginEvent event) {
        String ipAddress = resolveIp(event);
        debugEvent("prelogin_start",
                "player", event.getUsername(),
                "ip", ipAddress);

        VelocityLang lang = langSupplier.get();
        StorageProvider storage = storageSupplier.get();
        boolean requirePremiumOnlineMode = false;
        boolean requirePremiumKeyAuthentication = false;
        boolean denyPremiumClaim = false;
        boolean premiumClaimFailedOffline = false;
        UUID expectedPremiumUuid = null;
        boolean claimMode = identityClaimService.isLocalClaimModeEnabled();
        boolean proxyCustomMode = identityClaimService.isProxyCustomEnabled();
        PremiumVerifier.PremiumCheckResult premiumCheck = null;

        var activeBan = storage.findActiveBan(ipAddress).join();
        debugEvent("prelogin_ip_ban_check",
                "player", event.getUsername(),
                "ip", ipAddress,
                "banned", activeBan.isPresent());
        if (activeBan.isPresent()) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-ip-banned")));
            return;
        }

        var trustedIp = storage.findTrustedIp(event.getUsername()).join();
        if (trustedIp.isPresent() && !trustedIp.get().equalsIgnoreCase(ipAddress)) {
            debugEvent("prelogin_trusted_ip_mismatch",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "trusted_ip", trustedIp.get());
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-trusted-ip-mismatch")));
            return;
        }

        boolean inWhitelist = storage.isInPremiumIpWhitelist(event.getUsername()).join();
        if (inWhitelist) {
            boolean ipAllowed = storage.isPremiumIpWhitelisted(event.getUsername(), ipAddress).join();
            debugEvent("prelogin_premium_whitelist_check",
                    "player", event.getUsername(),
                    "ip", ipAddress,
                    "ip_allowed", ipAllowed);
            if (!ipAllowed) {
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        lang.message("kick-whitelist-ip-required")));
                return;
            }
            whitelistEnforcementStore.remember(event.getUsername(), ipAddress);
        }

        boolean automaticClaimMode = identityClaimService.isAutomaticClaimOnFirstJoinEnabled();
        boolean proudXKeyAuthMode = proxyCustomMode || automaticClaimMode;
        if (claimMode) {
            var effectiveClaim = identityClaimService.resolveEffectiveClaim(event.getUsername()).join();
            if (effectiveClaim.orElse(null) == AccountType.PREMIUM) {
                if (proudXKeyAuthMode) {
                    premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
                    if (premiumCheck.premium()) {
                        expectedPremiumUuid = premiumCheck.resolvedUuid();
                    }
                    pendingPremiumAuthStore.remember(connectionKey(event), event.getUsername());
                    ProudAuthSettings.LegacyUnsupportedAction legacyAction = legacyUnsupportedAction(event);
                    if (legacyAction == null) {
                        requirePremiumKeyAuthentication = true;
                        debugEvent("prelogin_claimed_premium_key_authentication",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "expected_uuid", expectedPremiumUuid);
                    } else if (legacyAction == ProudAuthSettings.LegacyUnsupportedAction.FORCE_ONLINE) {
                        requirePremiumOnlineMode = true;
                        debugEvent("prelogin_claimed_premium_legacy_force_online",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion());
                    } else {
                        denyPremiumClaim = true;
                        pendingPremiumAuthStore.forget(connectionKey(event));
                        debugEvent("prelogin_claimed_premium_legacy_denied",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                "action", legacyAction);
                    }
                } else {
                    requirePremiumOnlineMode = true;
                    debugEvent("prelogin_claimed_premium_force_online_mode",
                            "player", event.getUsername(),
                            "ip", ipAddress);
                }
            } else if (effectiveClaim.isEmpty()) {
                var pendingClaim = identityClaimService.findPendingClaim(event.getUsername(), ipAddress).join();
                if (pendingClaim.map(claim -> claim.claimType() == AccountType.PREMIUM).orElse(false)) {
                    identityClaimService.clearPendingClaim(event.getUsername()).join();
                    pendingPremiumAuthStore.remember(connectionKey(event), event.getUsername());
                    if (proudXKeyAuthMode) {
                        premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
                        if (premiumCheck.premium()) {
                            expectedPremiumUuid = premiumCheck.resolvedUuid();
                        }
                        ProudAuthSettings.LegacyUnsupportedAction legacyAction = legacyUnsupportedAction(event);
                        if (legacyAction == null) {
                            requirePremiumKeyAuthentication = true;
                            premiumClaimFailureStore.remember(event.getUsername(), ipAddress);
                            debugEvent("prelogin_pending_premium_key_authentication",
                                    "player", event.getUsername(),
                                    "ip", ipAddress,
                                    "expected_uuid", expectedPremiumUuid,
                                    "expires_at", pendingClaim.get().expiresAt());
                        } else if (legacyAction == ProudAuthSettings.LegacyUnsupportedAction.FORCE_ONLINE) {
                            requirePremiumOnlineMode = true;
                            debugEvent("prelogin_pending_premium_legacy_force_online",
                                    "player", event.getUsername(),
                                    "ip", ipAddress,
                                    "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                    "expires_at", pendingClaim.get().expiresAt());
                        } else if (legacyAction == ProudAuthSettings.LegacyUnsupportedAction.FORCE_SP) {
                            pendingPremiumAuthStore.forget(connectionKey(event));
                            premiumClaimFailureStore.remember(event.getUsername(), ipAddress);
                            premiumClaimFailedOffline = true;
                            debugEvent("prelogin_pending_premium_legacy_force_sp",
                                    "player", event.getUsername(),
                                    "ip", ipAddress,
                                    "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                    "expires_at", pendingClaim.get().expiresAt());
                        } else {
                            pendingPremiumAuthStore.forget(connectionKey(event));
                            denyPremiumClaim = true;
                            debugEvent("prelogin_pending_premium_legacy_denied",
                                    "player", event.getUsername(),
                                    "ip", ipAddress,
                                    "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                    "expires_at", pendingClaim.get().expiresAt());
                        }
                    } else {
                        requirePremiumOnlineMode = true;
                        debugEvent("prelogin_pending_premium_force_online_mode",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "expires_at", pendingClaim.get().expiresAt());
                    }
                } else if (automaticClaimMode) {
                    if (pendingClaim.map(claim -> claim.claimType() == AccountType.CRACKED).orElse(false)) {
                        premiumClaimFailedOffline = true;
                        debugEvent("prelogin_auto_claim_existing_cracked_pending",
                                "player", event.getUsername(),
                                "ip", ipAddress,
                                "expires_at", pendingClaim.get().expiresAt());
                    } else {
                        premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
                        if (premiumCheck.premium()) {
                            expectedPremiumUuid = premiumCheck.resolvedUuid();
                            pendingPremiumAuthStore.remember(connectionKey(event), event.getUsername());
                            ProudAuthSettings.LegacyUnsupportedAction legacyAction = legacyUnsupportedAction(event);
                            if (legacyAction == null) {
                                requirePremiumKeyAuthentication = true;
                                debugEvent("prelogin_auto_claim_premium_key_authentication",
                                        "player", event.getUsername(),
                                        "ip", ipAddress,
                                        "expected_uuid", expectedPremiumUuid);
                            } else if (legacyAction == ProudAuthSettings.LegacyUnsupportedAction.FORCE_ONLINE) {
                                requirePremiumOnlineMode = true;
                                debugEvent("prelogin_auto_claim_premium_legacy_force_online",
                                        "player", event.getUsername(),
                                        "ip", ipAddress,
                                        "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                        "premium_uuid", expectedPremiumUuid);
                            } else if (legacyAction == ProudAuthSettings.LegacyUnsupportedAction.FORCE_SP) {
                                pendingPremiumAuthStore.forget(connectionKey(event));
                                identityClaimService.beginPendingClaim(event.getUsername(), AccountType.CRACKED, ipAddress).join();
                                premiumClaimFailedOffline = true;
                                debugEvent("prelogin_auto_claim_premium_legacy_force_sp",
                                        "player", event.getUsername(),
                                        "ip", ipAddress,
                                        "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                        "premium_uuid", expectedPremiumUuid);
                            } else {
                                pendingPremiumAuthStore.forget(connectionKey(event));
                                denyPremiumClaim = true;
                                debugEvent("prelogin_auto_claim_premium_legacy_denied",
                                        "player", event.getUsername(),
                                        "ip", ipAddress,
                                        "protocol", event.getConnection().getProtocolVersion().getMostRecentSupportedVersion(),
                                        "premium_uuid", expectedPremiumUuid);
                            }
                        } else {
                            identityClaimService.beginPendingClaim(event.getUsername(), AccountType.CRACKED, ipAddress).join();
                            premiumClaimFailedOffline = true;
                            debugEvent("prelogin_auto_claim_non_premium_cracked_pending",
                                    "player", event.getUsername(),
                                    "ip", ipAddress);
                        }
                    }
                }
            }
            if (inWhitelist || requirePremiumOnlineMode) {
                premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
            }
            if (inWhitelist) {
                if (premiumCheck == null || !premiumCheck.premium()) {
                    debugEvent("prelogin_whitelist_requires_premium",
                        "player", event.getUsername(),
                        "ip", ipAddress);
                    event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                            lang.message("kick-whitelist-premium-required")));
                    return;
                }
                requirePremiumOnlineMode = true;
            }
        } else {
            premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
            if (premiumCheck.premium()) {
                requirePremiumOnlineMode = true;
                debugEvent("prelogin_force_online_mode",
                        "player", event.getUsername(),
                        "ip", ipAddress,
                        "premium_uuid", premiumCheck.resolvedUuid(),
                        "resolved_name", premiumCheck.resolvedName());
            } else if (inWhitelist) {
                debugEvent("prelogin_whitelist_requires_premium",
                        "player", event.getUsername(),
                        "ip", ipAddress);
                event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                        lang.message("kick-whitelist-premium-required")));
                return;
            }
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

        String authEntryTarget = routingSupplier.get().hasAuthEntryServer() ? routingSupplier.get().authEntryServer() : "";
        VelocityBackendJoinProbeService.ProbeStatus probeStatus = backendJoinProbeService.probe(event.getUsername(), ipAddress, authEntryTarget);
        debugEvent("prelogin_backend_probe",
                "player", event.getUsername(),
                "ip", ipAddress,
                "target_server", authEntryTarget,
                "status", probeStatus);
        if (probeStatus == VelocityBackendJoinProbeService.ProbeStatus.TIMEOUT
                || probeStatus == VelocityBackendJoinProbeService.ProbeStatus.ERROR) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(lang.message("kick-backend-unavailable")));
            return;
        }

        if (denyPremiumClaim) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
                    lang.message("kick-premium-custom-legacy-unsupported")));
        } else if (requirePremiumOnlineMode) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOnlineMode());
        } else if (requirePremiumKeyAuthentication) {
            event.setResult(ProudXPreLoginResults.tryKeyAuthentication(expectedPremiumUuid)
                    .orElseGet(PreLoginEvent.PreLoginComponentResult::forceOfflineMode));
        } else if (premiumClaimFailedOffline) {
            event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
        }
    }

    private void debugEvent(String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), DebugChannel.IP_BAN_FLOW, eventName, keyValues);
    }

    private String resolveIp(PreLoginEvent event) {
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private String connectionKey(PreLoginEvent event) {
        return VelocityOnlineModeDisconnectAdvice.connectionKey(event.getConnection().getRemoteAddress());
    }

    private ProudAuthSettings.LegacyUnsupportedAction legacyUnsupportedAction(PreLoginEvent event) {
        if (event.getConnection().getProtocolVersion().noLessThan(ProtocolVersion.MINECRAFT_1_19_1)
                && ProudXPreLoginResults.isTryKeyAuthenticationAvailable()) {
            return null;
        }
        return settingsSupplier.get().premium().legacyUnsupportedAction();
    }
}
