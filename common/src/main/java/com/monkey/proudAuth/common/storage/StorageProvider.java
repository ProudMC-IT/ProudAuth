package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.config.ProudAuthSettings;

public interface StorageProvider extends
        AccountStorage,
        SessionStorage,
        BackendJoinProbeStorage,
        BridgeAssertionStorage,
        IpBanStorage,
        StatsStorage,
        AutoCloseable {

    void init();

    void reload(ProudAuthSettings settings);

    @Override
    void close();
}
