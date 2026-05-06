package com.monkey.proudAuth.common.monitor;

import java.security.SecureRandom;
import java.util.HexFormat;

public final class MonitorSessionIdGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public String generateShortId(String prefix) {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return prefix + "_" + HexFormat.of().formatHex(bytes);
    }
}