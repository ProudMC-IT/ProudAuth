package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.network.BackendNetworkSyncService;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerQuitListener implements Listener {

    private final AuthService authService;
    private final PlayerProtection playerProtection;
    private final IdentityClaimService identityClaimService;
    private final BackendNetworkSyncService networkSyncService;

    public PlayerQuitListener(
            AuthService authService,
            PlayerProtection playerProtection,
            IdentityClaimService identityClaimService,
            BackendNetworkSyncService networkSyncService
    ) {
        this.authService = authService;
        this.playerProtection = playerProtection;
        this.identityClaimService = identityClaimService;
        this.networkSyncService = networkSyncService;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerProtection.clear(event.getPlayer());
        String ipAddress = event.getPlayer().getAddress() == null
                ? "unknown"
                : event.getPlayer().getAddress().getAddress().getHostAddress();
        if (identityClaimService.isLocalClaimModeEnabled()) {
            identityClaimService.snapshot(event.getPlayer().getName(), ipAddress)
                    .thenCompose(snapshot -> snapshot.finalClaim().isEmpty() && snapshot.pendingClaim().orElse(null) == AccountType.CRACKED
                            ? identityClaimService.clearPendingClaim(event.getPlayer().getName())
                            : java.util.concurrent.CompletableFuture.completedFuture(null))
                    .exceptionally(ignored -> null)
                    .join();
        }
        authService.untrackPlayer(event.getPlayer().getUniqueId());
        networkSyncService.forget(event.getPlayer().getUniqueId());
    }
}
