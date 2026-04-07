package com.monkey.proudAuth.common.model;

import java.util.UUID;

public record AuthPlayer(UUID uuid, String username, AccountType accountType, AuthState authState) {

    public boolean isAuthenticated() {
        return authState != AuthState.UNAUTHENTICATED;
    }

    public AuthPlayer withState(AuthState state) {
        return new AuthPlayer(uuid, username, accountType, state);
    }
}
