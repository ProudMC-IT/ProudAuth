package com.monkey.proudAuth.common.monitor;

import java.time.Duration;

public final class ProudAuthMonitorConstants {

    public static final String PANEL_URL = "https://panel.monkeymoon104.it";
    public static final String AGENT_WS_URL = "wss://api.monkeymoon104.it/v1/agent";
    public static final String DEFAULT_NETWORK_NAME = "ProudAuth Network";
    public static final Duration SESSION_TTL = Duration.ofMinutes(30);

    private ProudAuthMonitorConstants() {
    }
}