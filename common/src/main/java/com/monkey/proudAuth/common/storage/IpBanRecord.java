package com.monkey.proudAuth.common.storage;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;

public record IpBanRecord(String ip, Instant expiresAt, @Nullable String reason) {
}
