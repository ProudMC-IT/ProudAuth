package com.monkey.proudAuth.velocity.listeners;

import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.util.GameProfile;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.function.Supplier;

public final class VelocityGameProfileListener {

    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final Supplier<ProxyBridgeService> bridgeServiceSupplier;
    private final Supplier<Boolean> debugEnabledSupplier;
    private final Logger logger;

    public VelocityGameProfileListener(
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            Supplier<ProxyBridgeService> bridgeServiceSupplier,
            Supplier<Boolean> debugEnabledSupplier,
            Logger logger
    ) {
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.bridgeServiceSupplier = bridgeServiceSupplier;
        this.debugEnabledSupplier = debugEnabledSupplier;
        this.logger = logger;
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        GameProfile currentProfile = event.getGameProfile();
        GameProfile resolvedProfile = currentProfile;
        UUID offlineUuid = PremiumVerifier.offlineUuid(event.getUsername());
        AccountType accountType;
        debug("Velocity profile request player=%s currentUuid=%s offlineUuid=%s",
                event.getUsername(),
                currentProfile.getId(),
                offlineUuid);

        if (!currentProfile.getId().equals(offlineUuid)) {
            accountType = AccountType.PREMIUM;
            debug("Velocity forwarded premium profile player=%s uuid=%s", event.getUsername(), currentProfile.getId());
        } else {
            var premiumCheck = premiumVerifierSupplier.get().verify(event.getUsername()).join();
            debug("Velocity premium check player=%s premium=%s resolvedUuid=%s resolvedName=%s",
                    event.getUsername(),
                    premiumCheck.premium(),
                    premiumCheck.resolvedUuid(),
                    premiumCheck.resolvedName());
            if (premiumCheck.premium()) {
                resolvedProfile = currentProfile
                        .withId(premiumCheck.resolvedUuid())
                        .withName(premiumCheck.resolvedName())
                        .withProperties(currentProfile.getProperties());

                event.setGameProfile(resolvedProfile);
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
        debug("Velocity bridge publish player=%s resolvedUuid=%s resolvedName=%s accountType=%s ip=%s bridgeOperational=%s",
                event.getUsername(),
                resolvedProfile.getId(),
                resolvedProfile.getName(),
                accountType,
                ipAddress,
                bridgeServiceSupplier.get().isOperational());

        bridgeServiceSupplier.get()
                .publish(event.getUsername(), resolvedProfile.getName(), resolvedProfile.getId(), accountType, ipAddress)
                .exceptionally(exception -> {
                    debug("Velocity bridge publish error player=%s error=%s", event.getUsername(), exception.getMessage());
                    return null;
                })
                .join();
        debug("Velocity bridge publish complete player=%s resolvedUuid=%s", event.getUsername(), resolvedProfile.getId());
    }

    private void debug(String template, Object... args) {
        if (!debugEnabledSupplier.get()) {
            return;
        }
        logger.info("[DEBUG] " + template.formatted(args));
    }
}
