package com.monkey.proudAuth.common.auth;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthPlayer;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.storage.AccountRecord;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuthService {

    void trackPlayer(UUID uuid, String username, AccountType accountType);

    void untrackPlayer(UUID uuid);

    void markUnauthenticated(UUID uuid);

    Optional<AuthPlayer> player(UUID uuid);

    boolean isAuthenticated(UUID uuid);

    boolean requiresAuthentication(UUID uuid);

    int authenticatedCount();

    CompletableFuture<LoginResult> login(UUID uuid, String username, String ipAddress, String password);

    CompletableFuture<RegisterResult> register(UUID uuid, String username, AccountType accountType, String ipAddress, String password, String confirmation);

    CompletableFuture<ChangePasswordResult> changePassword(UUID uuid, String username, String oldPassword, String newPassword, String confirmation);

    CompletableFuture<Void> logout(UUID uuid);

    CompletableFuture<AuthenticationResult> autoAuthenticate(UUID uuid, String username, AccountType accountType, AuthState authState, String ipAddress);

    CompletableFuture<TotpSubmissionResult> submitTotp(UUID uuid, String username, String code);

    CompletableFuture<TotpSetupInitResult> initTotpSetup(UUID uuid, String username);

    CompletableFuture<TotpSetupConfirmResult> confirmTotpSetup(UUID uuid, String code);

    CompletableFuture<TotpDisableResult> disableTotp(UUID uuid, String code);

    CompletableFuture<TotpFlowResult> updateTotpFlow(UUID uuid, String username, boolean alwaysRequired);

    CompletableFuture<TotpFlowResult> fetchTotpFlow(UUID uuid, String username);

    CompletableFuture<TotpChallengeResult> authenticateWithTotpGate(
            UUID uuid,
            String username,
            AccountType accountType,
            AuthState authState,
            String ipAddress
    );

    boolean hasPendingTotpChallenge(UUID uuid);

    CompletableFuture<Optional<AccountRecord>> findAccountByUsername(String username);

    CompletableFuture<Boolean> resetPassword(String username, String newPassword);

    void reload(ProudAuthSettings settings);

    enum LoginStatus {
        SUCCESS,
        ALREADY_AUTHENTICATED,
        PREMIUM_ACCOUNT,
        NEEDS_REGISTER,
        WRONG_PASSWORD,
        LOCKED,
        TOTP_REQUIRED
    }

    record LoginResult(LoginStatus status, AccountType accountType, int attempts, int maxAttempts, long remainingSeconds) {
    }

    enum RegisterStatus {
        SUCCESS,
        ALREADY_REGISTERED,
        PREMIUM_ACCOUNT,
        MOJANG_UNAVAILABLE,
        PASSWORD_MISMATCH,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_INVALID
    }

    record RegisterResult(RegisterStatus status) {
    }

    enum ChangePasswordStatus {
        SUCCESS,
        WRONG_OLD_PASSWORD,
        PREMIUM_ACCOUNT,
        MOJANG_UNAVAILABLE,
        PASSWORD_MISMATCH,
        PASSWORD_TOO_SHORT,
        PASSWORD_TOO_LONG,
        PASSWORD_INVALID,
        NOT_REGISTERED
    }

    record ChangePasswordResult(ChangePasswordStatus status) {
    }

    record AuthenticationResult(AccountType accountType, AuthState authState) {
    }

    enum TotpSubmissionStatus {
        SUCCESS,
        INVALID,
        NOT_REQUIRED
    }

    record TotpSubmissionResult(TotpSubmissionStatus status, AccountType accountType) {
    }

    enum TotpSetupInitStatus {
        SUCCESS,
        DISABLED_IN_CONFIG,
        ALREADY_ENABLED,
        NOT_REGISTERED
    }

    record TotpSetupInitResult(
            TotpSetupInitStatus status,
            String secret,
            String uri,
            String qrCodeUrl,
            long expiresAtEpochSecond,
            int maxAttempts
    ) {
    }

    enum TotpSetupConfirmStatus {
        SUCCESS,
        INVALID,
        SETUP_NOT_INITIALIZED,
        SETUP_EXPIRED,
        SETUP_ATTEMPTS_EXHAUSTED
    }

    record TotpSetupConfirmResult(
            TotpSetupConfirmStatus status,
            int attemptsRemaining,
            long expiresAtEpochSecond
    ) {
    }

    enum TotpDisableStatus {
        SUCCESS,
        INVALID,
        NOT_ENABLED
    }

    record TotpDisableResult(TotpDisableStatus status) {
    }

    enum TotpFlowStatus {
        SUCCESS,
        NOT_ENABLED,
        NOT_REGISTERED
    }

    record TotpFlowResult(TotpFlowStatus status, boolean alwaysRequired) {
    }

    enum TotpChallengeStatus {
        AUTHENTICATED,
        TOTP_REQUIRED
    }

    record TotpChallengeResult(TotpChallengeStatus status, boolean suspiciousAccess) {
    }
}
