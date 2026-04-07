package com.monkey.proudAuth.bootstrap;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.security.TotpService;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.StorageProvider;

public record ProudAuthRuntime(
        PlatformType platformType,
        ProudAuthSettings settings,
        StorageProvider storage,
        SessionManager sessionManager,
        PremiumVerifier premiumVerifier,
        ProxyBridgeService bridgeService,
        BruteForceGuard bruteForceGuard,
        TotpService totpService,
        AuthService authService
) {
}
