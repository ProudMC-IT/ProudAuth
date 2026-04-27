package com.monkey.proudAuth.common.storage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface PremiumIpWhitelistStorage {

    CompletableFuture<Void> addPremiumIpWhitelist(String username, String ip);

    CompletableFuture<Boolean> removePremiumIpWhitelist(String username, String ip);

    CompletableFuture<List<String>> listPremiumIpWhitelist(String username);

    CompletableFuture<Boolean> isInPremiumIpWhitelist(String username);

    CompletableFuture<Boolean> isPremiumIpWhitelisted(String username, String ip);
}
