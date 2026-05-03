package com.monkey.proudAuth.common.bridge;

import java.util.Locale;

public enum BridgeJoinMode {
    AUTH_ENTRY,
    NETWORK_TRANSFER;

    public static BridgeJoinMode from(String raw) {
        try {
            return BridgeJoinMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return NETWORK_TRANSFER;
        }
    }
}
