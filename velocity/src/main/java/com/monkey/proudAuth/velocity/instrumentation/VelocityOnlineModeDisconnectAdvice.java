package com.monkey.proudAuth.velocity.instrumentation;

import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.velocity.session.VelocityPremiumClaimFailureStore;
import com.monkey.proudAuth.velocity.session.VelocityPendingPremiumAuthStore;
import net.bytebuddy.asm.Advice;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Objects;

public final class VelocityOnlineModeDisconnectAdvice {

    private static final String ONLINE_MODE_ONLY_KEY = "velocity.error.online-mode-only";
    private static volatile Component customDisconnectMessage = defaultMessage();
    private static volatile Component claimFailedDisconnectMessage = defaultClaimFailedMessage();
    private static volatile IdentityClaimService identityClaimService;
    private static volatile VelocityPendingPremiumAuthStore pendingPremiumAuthStore;
    private static volatile VelocityPremiumClaimFailureStore premiumClaimFailureStore;

    private VelocityOnlineModeDisconnectAdvice() {
    }

    public static void configure(
            IdentityClaimService configuredIdentityClaimService,
            VelocityPendingPremiumAuthStore configuredPendingPremiumAuthStore,
            VelocityPremiumClaimFailureStore configuredPremiumClaimFailureStore
    ) {
        identityClaimService = configuredIdentityClaimService;
        pendingPremiumAuthStore = configuredPendingPremiumAuthStore;
        premiumClaimFailureStore = configuredPremiumClaimFailureStore;
    }

    public static void updateMessages(String onlineModeDeniedMiniMessage, String claimFailedMiniMessage) {
        try {
            String resolved = onlineModeDeniedMiniMessage == null || onlineModeDeniedMiniMessage.isBlank()
                    ? defaultMiniMessage()
                    : onlineModeDeniedMiniMessage;
            customDisconnectMessage = MiniMessage.miniMessage().deserialize(resolved);
        } catch (Throwable ignored) {
            customDisconnectMessage = defaultMessage();
        }
        try {
            String resolved = claimFailedMiniMessage == null || claimFailedMiniMessage.isBlank()
                    ? defaultClaimFailedMiniMessage()
                    : claimFailedMiniMessage;
            claimFailedDisconnectMessage = MiniMessage.miniMessage().deserialize(resolved);
        } catch (Throwable ignored) {
            claimFailedDisconnectMessage = defaultClaimFailedMessage();
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This Object inbound, @Advice.Argument(value = 0, readOnly = false) Component reason) {
        if (!(reason instanceof TranslatableComponent translatable)) {
            return;
        }
        if (!ONLINE_MODE_ONLY_KEY.equals(translatable.key())) {
            return;
        }
        boolean pendingClaimCleared = clearPendingPremiumClaim(inbound, true);
        reason = pendingClaimCleared
                ? Objects.requireNonNullElseGet(
                        claimFailedDisconnectMessage,
                        VelocityOnlineModeDisconnectAdvice::defaultClaimFailedMessage
                )
                : Objects.requireNonNullElseGet(customDisconnectMessage, VelocityOnlineModeDisconnectAdvice::defaultMessage);
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onCleanup(@Advice.This Object inbound) {
        clearPendingPremiumClaim(inbound, false);
    }

    public static void reset() {
        identityClaimService = null;
        pendingPremiumAuthStore = null;
        premiumClaimFailureStore = null;
    }

    private static boolean clearPendingPremiumClaim(Object inbound, boolean replaceDisconnectMessage) {
        IdentityClaimService claimService = identityClaimService;
        VelocityPendingPremiumAuthStore authStore = pendingPremiumAuthStore;
        VelocityPremiumClaimFailureStore failureStore = premiumClaimFailureStore;
        if (claimService == null || authStore == null) {
            return false;
        }

        String connectionKey = connectionKey(inbound);
        if (connectionKey == null) {
            return false;
        }

        String username = authStore.consume(connectionKey).orElse(null);
        if (username == null) {
            return false;
        }
        claimService.clearPendingClaim(username).join();
        if (failureStore != null) {
            String ipAddress = ipAddress(inbound);
            if (ipAddress != null) {
                failureStore.remember(username, ipAddress);
            }
        }
        return true;
    }

    private static String connectionKey(Object inbound) {
        try {
            Object address = inbound.getClass().getMethod("getRemoteAddress").invoke(inbound);
            if (!(address instanceof InetSocketAddress socketAddress)) {
                return null;
            }
            return connectionKey(socketAddress);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String connectionKey(SocketAddress address) {
        if (!(address instanceof InetSocketAddress socketAddress)) {
            return null;
        }
        return connectionKey(socketAddress);
    }

    private static String ipAddress(Object inbound) {
        try {
            Object address = inbound.getClass().getMethod("getRemoteAddress").invoke(inbound);
            if (!(address instanceof InetSocketAddress socketAddress)) {
                return null;
            }
            return ipAddress(socketAddress);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String connectionKey(InetSocketAddress socketAddress) {
        String host = ipAddress(socketAddress);
        if (host == null || host.isBlank()) {
            return null;
        }
        return host.toLowerCase(Locale.ROOT) + ":" + socketAddress.getPort();
    }

    private static String ipAddress(InetSocketAddress socketAddress) {
        String host = socketAddress.getAddress() != null
                ? socketAddress.getAddress().getHostAddress()
                : socketAddress.getHostString();
        if (host == null || host.isBlank()) {
            return null;
        }
        return host;
    }

    private static Component defaultMessage() {
        return MiniMessage.miniMessage().deserialize(defaultMiniMessage());
    }

    private static Component defaultClaimFailedMessage() {
        return MiniMessage.miniMessage().deserialize(defaultClaimFailedMiniMessage());
    }

    private static String defaultMiniMessage() {
        return "<dark_gray>[</dark_gray><aqua>ProudMC</aqua><dark_gray>] </dark_gray>"
                + "<red>Accesso rifiutato</red><newline>"
                + "<gray>Questo nickname premium richiede un account Minecraft premium autenticato.</gray>";
    }

    private static String defaultClaimFailedMiniMessage() {
        return "<dark_gray>[</dark_gray><aqua>ProudMC</aqua><dark_gray>] </dark_gray>"
                + "<red>Verifica premium fallita</red><newline>"
                + "<gray>Non e stato possibile confermare questo nickname come premium.</gray><newline>"
                + "<yellow>Rientra e scegli di nuovo <white>/sp</white><yellow> oppure <white>/premium</white><yellow>.</yellow>";
    }
}
