package com.monkey.proudAuth.commands.admin.support;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.identity.IdentityClaimService;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.config.LangConfig;
import com.monkey.proudAuth.config.PluginConfig;
import com.monkey.proudAuth.network.BackendNetworkSyncService;
import com.monkey.proudAuth.protection.PlayerProtection;
import org.bukkit.plugin.java.JavaPlugin;

public record AdminCommandContext(
        JavaPlugin plugin,
        PluginConfig pluginConfig,
        LangConfig langConfig,
        AuthService authService,
        PlayerProtection playerProtection,
        BruteForceGuard bruteForceGuard,
        StorageProvider storage,
        SessionManager sessionManager,
        IdentityClaimService identityClaimService,
        PremiumVerifier premiumVerifier,
        BackendNetworkSyncService networkSyncService,
        Runnable reloadAction
) {
}
