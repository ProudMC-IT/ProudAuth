package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

public interface StorageProvider extends
        AccountStorage,
        IdentityClaimStorage,
        TrustedIpStorage,
        PremiumIpWhitelistStorage,
        SessionStorage,
        BackendJoinProbeStorage,
        BridgeAssertionStorage,
        IpHistoryStorage,
        IpBanStorage,
        NetworkConfigStorage,
        StatsStorage,
        AutoCloseable {

    void init();

    void reload(ProudAuthSettings settings);

    @Override
    void close();
}
