package com.monkey.proudAuth.common.logging;

public enum DebugChannel {
    PLAYER_RESOLUTION("player-resolution"),
    SESSION_FLOW("session-flow"),
    PREMIUM_FLOW("premium-flow"),
    BRIDGE_FLOW("bridge-flow"),
    SECURITY_FLOW("security-flow"),
    PROTECTION_FLOW("protection-flow"),
    IP_BAN_FLOW("ip-ban-flow"),
    PROFILE_FLOW("profile-flow"),
    MOVEMENT_AUDIT("movement-audit"),
    TELEPORT_AUDIT("teleport-audit"),
    COMMAND_FLOW("command-flow");

    private final String configKey;

    DebugChannel(String configKey) {
        this.configKey = configKey;
    }

    public String configKey() {
        return configKey;
    }
}
