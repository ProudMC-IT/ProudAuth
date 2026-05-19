package com.monkey.proudAuth.common.storage;

import java.util.Locale;
import java.util.Optional;

public enum DelegatedAccessHistoryDirection {
    IN,
    OUT;

    public static Optional<DelegatedAccessHistoryDirection> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
