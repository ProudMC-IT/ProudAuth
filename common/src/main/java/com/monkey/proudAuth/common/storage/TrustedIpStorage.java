package com.monkey.proudAuth.common.storage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface TrustedIpStorage {

    CompletableFuture<Void> setTrustedIp(String username, String ipAddress);

    CompletableFuture<Optional<String>> findTrustedIp(String username);
}
