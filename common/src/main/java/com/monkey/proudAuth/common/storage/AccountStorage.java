package com.monkey.proudAuth.common.storage;

import com.monkey.proudAuth.common.model.AccountType;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AccountStorage {

    CompletableFuture<Optional<AccountRecord>> findAccountByUuid(UUID uuid);

    CompletableFuture<Optional<AccountRecord>> findAccountByUsername(String username);

    CompletableFuture<List<String>> listAccountUsernames(String prefix, int limit);

    CompletableFuture<Void> saveAccount(AccountRecord accountRecord);

    CompletableFuture<Void> updatePassword(UUID uuid, String passwordHash);

    CompletableFuture<Void> touchLogin(UUID uuid, String username, String ip, AccountType accountType);

    CompletableFuture<Void> updateTotpSecret(UUID uuid, @Nullable String encryptedSecret);

    CompletableFuture<Optional<String>> findTotpSecret(UUID uuid);

    CompletableFuture<Void> updateTotpFlow(UUID uuid, boolean alwaysRequired);
}
