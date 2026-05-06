package com.monkey.proudAuth.common.monitor;

public enum ProudAuthMonitorState {

    AUTHENTICATED,
    WAITING_LOGIN,
    WAITING_REGISTER,
    WAITING_2FA,
    LOCKED,
    UNKNOWN;

    public static ProudAuthMonitorState from(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }

        try {
            return ProudAuthMonitorState.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}