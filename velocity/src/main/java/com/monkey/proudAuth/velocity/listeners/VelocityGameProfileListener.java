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

        AccountType accountType;
        UUID accountUuid;
        String accountName;
        boolean premiumNameDetected = premiumCheck.premium();
        boolean premiumVerified = false;

        if (premiumCheck.premium()) {
            boolean authenticatedByProxy = event.isOnlineMode() || !currentProfile.getId().equals(offlineUuid);
            if (authenticatedByProxy) {
                accountType = AccountType.PREMIUM;
                accountUuid = currentProfile.getId();
                accountName = currentProfile.getName();
                premiumVerified = true;
                if (premiumRequiredByWhitelist) {
                    whitelistEnforcementStore.forget(event.getUsername(), ipAddress);
                }
            } else {
                accountType = AccountType.CRACKED;
                accountUuid = currentProfile.getId();
                accountName = currentProfile.getName();
                debugEvent(DebugChannel.PREMIUM_FLOW, "premium_unverified_after_prelogin",
                        "player", event.getUsername(),
                        "ip", ipAddress,
                        "event_online_mode", event.isOnlineMode(),
                        "current_profile_uuid", currentProfile.getId(),
                        "offline_uuid", offlineUuid,
                        "premium_uuid", premiumCheck.resolvedUuid());
            }
        } else {
            accountType = AccountType.CRACKED;
            accountUuid = currentProfile.getId();
            accountName = currentProfile.getName();

            if (premiumRequiredByWhitelist) {
                debugEvent(DebugChannel.PREMIUM_FLOW, "whitelist_premium_required_pending_disconnect",
                        "player", event.getUsername(),
                        "ip", ipAddress,
                        "current_uuid", currentProfile.getId());
            }
        }

        resolvedPlayerStore.remember(
                event.getUsername(),
                accountUuid,
                accountName,
                accountType,
                premiumNameDetected,
                premiumVerified
        );

        publishResolvedProfile(
                event.getUsername(),
                ipAddress,
                accountUuid,
                accountName,
                accountType
        );

        debugEvent(DebugChannel.PROFILE_FLOW, "profile_resolved",
                "player", event.getUsername(),
                "current_profile_uuid", currentProfile.getId(),
                "resolved_uuid", accountUuid,
                "account_type", accountType,
                "premium_check", premiumCheck.premium(),
                "premium_verified", premiumVerified,
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

        if (resolvedPlayer.premiumNameDetected() && !resolvedPlayer.premiumVerified()) {
            debugEvent(DebugChannel.PREMIUM_FLOW, "premium_login_denied_unverified",
                    "player", username,
                    "ip", ipAddress,
                    "account_uuid", resolvedPlayer.accountUuid());
            event.setResult(ResultedEvent.ComponentResult.denied(
                    premiumRequiredByWhitelist
                            ? langSupplier.get().message("kick-whitelist-premium-required")
                            : langSupplier.get().message("kick-premium-impersonation")));
        }

        if (premiumRequiredByWhitelist) {
            whitelistEnforcementStore.forget(username, ipAddress);
        }
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

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(debuggerSupplier.get(), channel, eventName, keyValues);
    }

    private String resolveIp(InboundConnection connection) {
        if (connection.getRemoteAddress() instanceof InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }
}
