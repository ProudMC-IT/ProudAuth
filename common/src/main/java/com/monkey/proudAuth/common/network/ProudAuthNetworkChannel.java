package com.monkey.proudAuth.common.network;

public final class ProudAuthNetworkChannel {

    public static final String CHANNEL_ID = "proudauth:sync";
    public static final String AUTH_COMPLETED = "AUTH_COMPLETED";
    public static final String AUTH_INVALIDATED = "AUTH_INVALIDATED";
    public static final String AUTH_STATE_UPDATE = "AUTH_STATE_UPDATE";

    private ProudAuthNetworkChannel() {
    }
}