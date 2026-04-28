package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface BridgeAssertionStorage {

    CompletableFuture<Void> saveProxyAssertion(ProxyBridgeAssertion assertion);

    CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertion(String username, String ip);

    CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertionByUsername(String username);

    CompletableFuture<Void> deleteProxyAssertion(String nonce);

    CompletableFuture<Integer> deleteExpiredProxyAssertions();
}
