package com.monkey.proudAuth.auth;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.logging.DebugChannel;
import com.monkey.proudAuth.common.logging.ProudAuthConsoleLogger;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.protection.PlayerProtection;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitJoinFlowService {

    private final JavaPlugin plugin;
    private final PluginConfig pluginConfig;
    private final LangConfig langConfig;
    private final AuthService authService;
    private final SessionManager sessionManager;
    private final PlayerProtection playerProtection;
    private final IdentityClaimService identityClaimService;
    private final ProudAuthConsoleLogger logger;

    public BukkitJoinFlowService(
            JavaPlugin plugin,
            PluginConfig pluginConfig,
            LangConfig langConfig,
            AuthService authService,
            SessionManager sessionManager,
            PlayerProtection playerProtection,
            IdentityClaimService identityClaimService,
            ProudAuthConsoleLogger logger
    ) {
        this.plugin = plugin;
        this.pluginConfig = pluginConfig;
        this.langConfig = langConfig;
        this.authService = authService;
        this.sessionManager = sessionManager;
        this.playerProtection = playerProtection;
        this.identityClaimService = identityClaimService;
        this.logger = logger;
    }

    public void handleJoin(Player player, ResolvedLogin resolvedLogin) {
        authService.trackPlayer(player.getUniqueId(), resolvedLogin.uuid(), player.getName(), resolvedLogin.accountType());

        boolean rawBypassPermission = player.hasPermission("proudauth.bypass.auth");
        boolean bypassAuth = rawBypassPermission && resolvedLogin.accountType() != AccountType.PREMIUM;
        debugEvent(DebugChannel.PLAYER_RESOLUTION, "join_bypass_check",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "raw_bypass", rawBypassPermission,
                "effective_bypass", bypassAuth,
                "account_type", resolvedLogin.accountType());

        if (bypassAuth) {
            handleBypassJoin(player, resolvedLogin);
            return;
        }

        if (identityClaimService.isClaimOnFirstJoinEnabled()) {
            identityClaimService.snapshot(player.getName(), resolvedLogin.ipAddress())
                    .whenComplete((claimSnapshot, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (exception != null) {
                            langConfig.send(player, "error-generic");
                            return;
                        }
                        if (claimSnapshot.finalClaim().isEmpty()) {
                            AccountType pendingClaimType = claimSnapshot.pendingClaim().orElse(null);
                            if (pendingClaimType == AccountType.CRACKED) {
                                continueJoinAfterClaimResolution(player, resolvedLogin);
                                return;
                            }
                            handleClaimChoiceJoin(player, pendingClaimType);
                            return;
                        }
                        continueJoinAfterClaimResolution(player, resolvedLogin);
                    }));
            return;
        }

        continueJoinAfterClaimResolution(player, resolvedLogin);
    }

    private void continueJoinAfterClaimResolution(Player player, ResolvedLogin resolvedLogin) {
        playerProtection.exitClaimChoicePhase(player);
        if (resolvedLogin.accountType() == AccountType.PREMIUM && pluginConfig.settings().premium().autoLogin()) {
            handlePremiumAutoLogin(player, resolvedLogin);
            return;
        }

        handleProtectedJoin(player, resolvedLogin);
    }

    private void handleBypassJoin(Player player, ResolvedLogin resolvedLogin) {
        playerProtection.exitClaimChoicePhase(player);
        debugEvent(DebugChannel.SECURITY_FLOW, "join_bypass_start",
                "player", player.getName(),
                "uuid", player.getUniqueId());
        authService.autoAuthenticate(player.getUniqueId(), player.getName(), resolvedLogin.accountType(), AuthState.AUTHENTICATED, ipAddress(player))
                .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (exception != null) {
                        debugEvent(DebugChannel.SECURITY_FLOW, "join_bypass_error",
                                "player", player.getName(),
                                "uuid", player.getUniqueId(),
                                "error", exception.getMessage());
                        langConfig.send(player, "error-generic");
                        return;
                    }
                    playerProtection.removeProtection(player);
                    debugEvent(DebugChannel.SECURITY_FLOW, "join_bypass_complete",
                            "player", player.getName(),
                            "uuid", player.getUniqueId());
                }));
    }

    private void handlePremiumAutoLogin(Player player, ResolvedLogin resolvedLogin) {
        playerProtection.exitClaimChoicePhase(player);
        debugEvent(DebugChannel.PREMIUM_FLOW, "join_premium_fastpath_start",
                "player", player.getName(),
                "uuid", player.getUniqueId());
        playerProtection.applyProtectionTransient(player);
        authService.authenticateWithTotpGate(
                        player.getUniqueId(),
                        player.getName(),
                        resolvedLogin.accountType(),
                        AuthState.PREMIUM_AUTO,
                        resolvedLogin.ipAddress()
                )
                .whenComplete((ignored, exception) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        playerProtection.removeProtection(player);
                        return;
                    }
                    if (exception != null) {
                        debugEvent(DebugChannel.PREMIUM_FLOW, "join_premium_fastpath_error",
                                "player", player.getName(),
                                "uuid", player.getUniqueId(),
                                "error", exception.getMessage());
                        playerProtection.removeProtection(player);
                        langConfig.send(player, "error-generic");
                        return;
                    }

                    if (ignored.status() == com.monkey.proudAuth.common.auth.AuthService.TotpChallengeStatus.TOTP_REQUIRED) {
                        playerProtection.upgradeToFullProtection(player);
                        debugEvent(DebugChannel.PREMIUM_FLOW, "join_premium_fastpath_totp_required",
                                "player", player.getName(),
                                "uuid", player.getUniqueId(),
                                "suspicious", ignored.suspiciousAccess());
                        langConfig.send(player, ignored.suspiciousAccess() ? "totp-required-suspicious" : "totp-required");
                        return;
                    }

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!player.isOnline()) {
                            return;
                        }
                        playerProtection.removeProtection(player);
                        debugEvent(DebugChannel.PREMIUM_FLOW, "join_premium_fastpath_complete",
                                "player", player.getName(),
                                "uuid", player.getUniqueId());
                        langConfig.send(player, "premium-auto-login");
                    }, 1L);
                }));
    }

    private void handleProtectedJoin(Player player, ResolvedLogin resolvedLogin) {
        playerProtection.exitClaimChoicePhase(player);
        playerProtection.applyProtection(player);
        debugEvent(DebugChannel.PROTECTION_FLOW, "join_protection_applied",
                "player", player.getName(),
                "uuid", player.getUniqueId());

        sessionManager.findValid(resolvedLogin.uuid(), resolvedLogin.ipAddress(), resolvedLogin.accountType())
                .thenCompose(optionalSession -> {
                    debugEvent(DebugChannel.SESSION_FLOW, "join_session_lookup",
                            "player", player.getName(),
                            "uuid", player.getUniqueId(),
                            "account_uuid", resolvedLogin.uuid(),
                            "session_present", optionalSession.isPresent());
                    if (optionalSession.isPresent()) {
                        return authService.authenticateWithTotpGate(
                                        player.getUniqueId(),
                                        player.getName(),
                                        resolvedLogin.accountType(),
                                        AuthState.AUTHENTICATED,
                                        resolvedLogin.ipAddress()
                                )
                                .thenApply(result -> result.status() == com.monkey.proudAuth.common.auth.AuthService.TotpChallengeStatus.TOTP_REQUIRED
                                        ? JoinOutcome.TOTP_REQUIRED
                                        : JoinOutcome.SESSION_RESTORED);
                    }

                    debugEvent(DebugChannel.SESSION_FLOW, "join_manual_auth_required",
                            "player", player.getName(),
                            "uuid", player.getUniqueId(),
                            "account_type", resolvedLogin.accountType(),
                            "premium_auto_login", pluginConfig.settings().premium().autoLogin());
                    return java.util.concurrent.CompletableFuture.completedFuture(JoinOutcome.REQUIRES_AUTH);
                })
                .whenComplete((outcome, exception) -> Bukkit.getScheduler().runTask(plugin, () -> completeProtectedJoin(player, outcome, exception)));
    }

    private void handleClaimChoiceJoin(Player player, AccountType pendingClaimType) {
        playerProtection.applyProtection(player);
        playerProtection.enterClaimChoicePhase(player);
        debugEvent(DebugChannel.PROTECTION_FLOW, "join_claim_choice_protection_applied",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "pending_claim", pendingClaimType);

        if (pendingClaimType == AccountType.PREMIUM) {
            langConfig.send(player, "claim-premium-reconnect-required");
            return;
        }

        langConfig.send(player, "claim-choice-required");
    }

    private void completeProtectedJoin(Player player, JoinOutcome outcome, Throwable exception) {
        if (!player.isOnline()) {
            return;
        }

        if (exception != null) {
            debugEvent(DebugChannel.SESSION_FLOW, "join_completion_error",
                    "player", player.getName(),
                    "uuid", player.getUniqueId(),
                    "error", exception.getMessage());
            langConfig.send(player, "error-generic");
            return;
        }

        debugEvent(DebugChannel.SESSION_FLOW, "join_outcome",
                "player", player.getName(),
                "uuid", player.getUniqueId(),
                "outcome", outcome);
        switch (outcome) {
            case SESSION_RESTORED -> {
                playerProtection.removeProtection(player);
                debugEvent(DebugChannel.PROTECTION_FLOW, "join_protection_removed",
                        "player", player.getName(),
                        "uuid", player.getUniqueId());
                langConfig.send(player, "session-restored", Placeholder.unparsed("player", player.getName()));
            }
            case TOTP_REQUIRED -> langConfig.send(player, "totp-required");
            case REQUIRES_AUTH -> langConfig.send(player, "not-authenticated");
        }
    }

    private String ipAddress(Player player) {
        return player.getAddress() == null ? "unknown" : player.getAddress().getAddress().getHostAddress();
    }

    private void debugEvent(DebugChannel channel, String eventName, Object... keyValues) {
        logger.debugEvent(pluginConfig.settings().debugger(), channel, eventName, keyValues);
    }

    private enum JoinOutcome {
        SESSION_RESTORED,
        TOTP_REQUIRED,
        REQUIRES_AUTH
    }
}
