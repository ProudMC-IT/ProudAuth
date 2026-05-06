package com.monkey.proudAuth.velocity.monitor;

import com.monkey.proudAuth.common.monitor.ProudAuthMonitorState;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class VelocityMonitorAuthStateStore {

    private final Map<String, ProudAuthMonitorState> states = new ConcurrentHashMap<>();

    public void update(String username, ProudAuthMonitorState state) {
        if (username == null || username.isBlank()) {
            return;
        }

        states.put(key(username), state == null ? ProudAuthMonitorState.UNKNOWN : state);
    }

    public Optional<ProudAuthMonitorState> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        return Optional.ofNullable(states.get(key(username)));
    }

    public void forget(String username) {
        if (username == null || username.isBlank()) {
            return;
        }

        states.remove(key(username));
    }

    private String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}