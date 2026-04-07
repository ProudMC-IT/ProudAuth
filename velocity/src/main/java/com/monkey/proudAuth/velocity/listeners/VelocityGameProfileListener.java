package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityGameProfileListener {

    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<Boolean> rewriteGameProfileSupplier;
    private final Supplier<ProudAuthSettings.Debugger> debuggerSupplier;
    private final ProudAuthConsoleLogger logger;

    public VelocityGameProfileListener(
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<Boolean> rewriteGameProfileSupplier,
            Supplier<ProudAuthSettings.Debugger> debuggerSupplier,
            ProudAuthConsoleLogger logger
    ) {
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.rewriteGameProfileSupplier = rewriteGameProfileSupplier;
        this.debuggerSupplier = debuggerSupplier;
        this.logger = logger;
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        GameProfile currentProfile = event.getGameProfile();
        GameProfile resolvedProfile = currentProfile;
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getUsername());
        AccountType accountType;
        debug(DebugChannel.PROFILE_FLOW, "Velocity profile request player=%s currentUuid=%s offlineUuid=%s",
                event.getUsername(),
                currentProfile.getId(),
                offlineUuid);

        if (!currentProfile.getId().equals(offlineUuid)) {
            accountType = AccountType.PREMIUM;
            debug(DebugChannel.PROFILE_FLOW, "Velocity forwarded premium profile player=%s uuid=%s", event.getUsername(), currentProfile.getId());
        } else {
            var premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
            debug(DebugChannel.PREMIUM_FLOW, "Velocity premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                    event.getUsername(),
                    premiumCheck.premium(),
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName());
            if (premiumCheck.premium()) {
                if (rewriteGameProfileSupplier.get()) {
                    resolvedProfile = currentProfile
                            .withId(premiumCheck.resolvedUuid())
                            .withName(premiumCheck.resolvedName())
                            .withProperties(currentProfile.getProperties());
                    event.setGameProfile(resolvedProfile);
                    debug(DebugChannel.PROFILE_FLOW, "Velocity premium profile rewritten player=%s forwardedUuid=%s resolvedUuid=%s",
                            event.getUsername(),
                            currentProfile.getId(),
                            resolvedProfile.getId());
                } else {
                    debug(DebugChannel.PROFILE_FLOW, "Velocity premium profile rewrite skipped player=%s forwardedUuid=%s resolvedPremiumUuid=%s",
                            event.getUsername(),
                            currentProfile.getId(),
                            premiumCheck.resolvedUuid());
                }
                accountType = AccountType.PREMIUM;
            } else {
                accountType = AccountType.CRACKED;
            }
        }

        String ipAddress = "unknown";
        if (event.getConnection().getRemoteAddress() instanceof InetSocketAddress socketAddress
                && socketAddress.getAddress() != null) {
            ipAddress = socketAddress.getAddress().getHostAddress();
        }
        debug(DebugChannel.BRIDGE_FLOW, "Velocity bridge publish player=%s resolvedUuid=%s resolvedName=%s accountType=%s ip=%s bridgeOperational=%s",
                event.getUsername(),
                resolvedProfile.getId(),
                resolvedProfile.getName(),
                accountType,
                ipAddress,
                bridgeServiceSupplier.get().isOperational());

        bridgeServiceSupplier.get()
                .publish(event.getUsername(), resolvedProfile.getName(), resolvedProfile.getId(), accountType, ipAddress)
                .exceptionally(exception -> {
                    debug(DebugChannel.BRIDGE_FLOW, "Velocity bridge publish error player=%s error=%s", event.getUsername(), exception.getMessage());
                    return null;
                })
                .join();
        debug(DebugChannel.BRIDGE_FLOW, "Velocity bridge publish complete player=%s resolvedUuid=%s", event.getUsername(), resolvedProfile.getId());
    }

    private void debug(DebugChannel channel, String template, Object... args) {
        logger.debug(debuggerSupplier.get(), channel, template, args);
    }
}
