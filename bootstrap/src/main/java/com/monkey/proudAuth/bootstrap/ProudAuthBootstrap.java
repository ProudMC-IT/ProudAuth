package com.monkey.proudAuth.bootstrap;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.auth.impl.AuthServiceImpl;
import com.monkey.proudAuth.common.bridge.ProxyBridgeService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.premium.impl.MojangPremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.security.TotpService;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.session.impl.SessionManagerImpl;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.common.storage.impl.MySQLStorage;

public final class ProudAuthBootstrap {

    public ProudAuthRuntime boot(PlatformType platformType, ProudAuthSettings settings) {
        StorageProvider storage = new MySQLStorage(settings);
        storage.init();
        SessionManager sessionManager = new SessionManagerImpl(storage, settings);
        PremiumVerifier premiumVerifier = new MojangPremiumVerifier(settings);
        ProxyBridgeService bridgeService = new ProxyBridgeService(storage, settings);
        BruteForceGuard bruteForceGuard = new BruteForceGuard(storage, settings);
        TotpService totpService = new TotpService(settings);
        AuthService authService = new AuthServiceImpl(storage, sessionManager, bruteForceGuard, premiumVerifier, totpService, settings);

        return new ProudAuthRuntime(
                platformType,
                settings,
                storage,
                sessionManager,
                premiumVerifier,
                bridgeService,
                bruteForceGuard,
                totpService,
                authService
        );
    }

    public ProudAuthRuntime reload(ProudAuthRuntime runtime, ProudAuthSettings settings) {
        runtime.storage().reload(settings);
        runtime.sessionManager().reload(settings);
        runtime.premiumVerifier().reload(settings);
        runtime.bridgeService().reload(settings);
        runtime.bruteForceGuard().reload(settings);
        runtime.totpService().reload(settings);
        runtime.authService().reload(settings);

        return new ProudAuthRuntime(
                runtime.platformType(),
                settings,
                runtime.storage(),
                runtime.sessionManager(),
                runtime.premiumVerifier(),
                runtime.bridgeService(),
                runtime.bruteForceGuard(),
                runtime.totpService(),
                runtime.authService()
        );
    }

    public void close(ProudAuthRuntime runtime) {
        runtime.storage().close();
    }
}
