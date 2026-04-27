package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.StorageProvider;
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
import com.velocitypowered.api.util.GameProfile;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityGameProfileListener {

    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final VelocityNetworkGuardService networkGuardService;
    private final VelocityWhitelistEnforcementStore whitelistEnforcementStore;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final ProudAuthConsoleLogger logger;

    public VelocityGameProfileListener(
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<StorageProvider> storageSupplier,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            VelocityNetworkGuardService networkGuardService,
            VelocityWhitelistEnforcementStore whitelistEnforcementStore,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            ProudAuthConsoleLogger logger
    ) {
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.storageSupplier = storageSupplier;
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

        AccountType accountType;
        GameProfile resolvedProfile;

        if (premiumCheck.premium()) {
            boolean uuidAlreadyCorrect = !currentProfile.getId().equals(offlineUuid);
            boolean trustedIpOverride = hasTrustedIpMatch(event.getUsername(), ipAddress);

            if (uuidAlreadyCorrect) {
                accountType = AccountType.PREMIUM;
                resolvedProfile = currentProfile;
            } else {
                resolvedProfile = currentProfile
                        .withId(premiumCheck.resolvedUuid())
                        .withName(premiumCheck.resolvedName())
                        .withProperties(currentProfile.getProperties());
                event.setGameProfile(resolvedProfile);
                accountType = AccountType.PREMIUM;

                if (trustedIpOverride) {
                    debugEvent(DebugChannel.PREMIUM_FLOW, "premium_trusted_ip_override",
                            "player", event.getUsername(),
                            "ip", ipAddress,
                            "offline_uuid", offlineUuid,
                            "resolved_uuid", premiumCheck.resolvedUuid());
                }
            }

            if (premiumRequiredByWhitelist) {
                whitelistEnforcementStore.forget(event.getUsername(), ipAddress);
            }
        } else {
            accountType = AccountType.CRACKED;
            resolvedProfile = currentProfile;

            if (premiumRequiredByWhitelist) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_pending_disconnect",
                        "player", event.getUsername(),
                        "ip", ipAddress,
                        "current_uuid", currentProfile.getId());
            }
        }

        resolvedPlayerStore.remember(
                event.getUsername(),
                resolvedProfile.getId(),
                resolvedProfile.getName(),
                accountType
        );

        networkGuardService.recordResolvedProfile(event.getUsername(), ipAddress, accountType);

        bridgeServiceSupplier.get()
                .publish(
                        event.getUsername(),
                        resolvedProfile.getName(),
                        resolvedProfile.getId(),
                        accountType,
                        ipAddress
                )
                .exceptionally(exception -> {
                    debugEvent(DebugChannel.BRIDGE_FLOW, "bridge_publish_error",
                            "player", event.getUsername(),
                            "error", exception.getMessage());
                    return null;
                })
                .join();

        debugEvent(DebugChannel.PROFILE_FLOW, "profile_resolved",
                "player", event.getUsername(),
                "uuid", resolvedProfile.getId(),
                "account_type", accountType,
                "premium_check", premiumCheck.premium(),
                "ip", ipAddress);
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onLogin(LoginEvent event) {
        String username = event.getPlayer().getUsername();
        String ipAddress = resolveIp(event.getPlayer());
        if (!whitelistEnforcementStore.requiresPremium(username, ipAddress)) {
            return;
        }

        VelocityResolvedPlayerStore.ResolvedPlayer resolvedPlayer = resolvedPlayerStore.find(username).orElse(null);
        if (resolvedPlayer == null || resolvedPlayer.accountType() != AccountType.PREMIUM) {
            debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_denied",
                    "player", username,
                    "ip", ipAddress,
                    "resolved_account_type", resolvedPlayer == null ? "missing" : resolvedPlayer.accountType());
            event.setResult(ResultedEvent.ComponentResult.denied(
                    langSupplier.get().message("kick-whitelist-premium-required")));
        }

        whitelistEnforcementStore.forget(username, ipAddress);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        whitelistEnforcementStore.forget(event.getPlayer().getUsername(), resolveIp(event.getPlayer()));
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

    private String resolveIp(InboundConnection connection) {
        if (connection.getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
