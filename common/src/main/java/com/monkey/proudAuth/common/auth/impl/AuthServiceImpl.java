package com.monkey.proudAuth.common.auth.impl;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthPlayer;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.security.TotpService;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.AccountStorage;
import com.monkey.proudAuth.common.util.HashUtil;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class AuthServiceImpl implements AuthService {

    private final AccountStorage storage;
    private final SessionManager sessionManager;
    private final BruteForceGuard bruteForceGuard;
    private final TotpService totpService;
    private final Map<UUID, AuthPlayer> trackedPlayers;
    private final Map<UUID, PendingTotpChallenge> pendingTotpChallenges;
    private volatile ProudAuthSettings settings;

    public AuthServiceImpl(
            AccountStorage storage,
            SessionManager sessionManager,
            BruteForceGuard bruteForceGuard,
            TotpService totpService,
            ProudAuthSettings settings
    ) {
        this.storage = storage;
        this.sessionManager = sessionManager;
        this.bruteForceGuard = bruteForceGuard;
        this.totpService = totpService;
        this.settings = settings;
        this.trackedPlayers = new ConcurrentHashMap<>();
        this.pendingTotpChallenges = new ConcurrentHashMap<>();
    }

    @Override
    public void trackPlayer(UUID uuid, String username, AccountType accountType) {
        trackedPlayers.put(uuid, new AuthPlayer(uuid, username, accountType, AuthState.UNAUTHENTICATED));
    }

    @Override
    public void untrackPlayer(UUID uuid) {
        trackedPlayers.remove(uuid);
        pendingTotpChallenges.remove(uuid);
    }

    @Override
    public void markUnauthenticated(UUID uuid) {
        trackedPlayers.computeIfPresent(uuid, (ignored, current) -> current.withState(AuthState.UNAUTHENTICATED));
        pendingTotpChallenges.remove(uuid);
    }

    @Override
    public Optional<AuthPlayer> player(UUID uuid) {
        return Optional.ofNullable(trackedPlayers.get(uuid));
    }

    @Override
    public boolean isAuthenticated(UUID uuid) {
        return player(uuid).map(AuthPlayer::isAuthenticated).orElse(false);
    }

    @Override
    public boolean requiresAuthentication(UUID uuid) {
        return !isAuthenticated(uuid);
    }

    @Override
    public int authenticatedCount() {
        return (int) trackedPlayers.values().stream().filter(AuthPlayer::isAuthenticated).count();
    }

    @Override
    public CompletableFuture<LoginResult> login(UUID uuid, String username, String ipAddress, String password) {
        if (isAuthenticated(uuid)) {
            return CompletableFuture.completedFuture(new LoginResult(
                    LoginStatus.ALREADY_AUTHENTICATED,
                    trackedAccountType(uuid, username),
                    0,
                    settings.security().maxAttempts(),
                    0
            ));
        }

        return bruteForceGuard.check(ipAddress)
                .thenCompose(lockStatus -> {
                    if (lockStatus.locked()) {
                        return CompletableFuture.completedFuture(new LoginResult(
                                LoginStatus.LOCKED,
                                trackedAccountType(uuid, username),
                                0,
                                settings.security().maxAttempts(),
                                lockStatus.remainingSeconds()
                        ));
                    }

                    return findAccount(uuid, username).thenCompose(optionalAccount -> handleLogin(uuid, username, password, ipAddress, optionalAccount));
                });
    }

    @Override
    public CompletableFuture<RegisterResult> register(UUID uuid, String username, AccountType accountType, String ipAddress, String password, String confirmation) {
        RegisterStatus validation = validateNewPassword(password, confirmation);
        if (validation != RegisterStatus.SUCCESS) {
            return CompletableFuture.completedFuture(new RegisterResult(validation));
        }

        return findAccount(uuid, username).thenCompose(optionalAccount -> {
            if (optionalAccount.isPresent() && optionalAccount.get().passwordHash() != null) {
                return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.ALREADY_REGISTERED));
            }

            AccountRecord accountRecord = new AccountRecord(
                    uuid,
                    username,
                    HashUtil.hash(password),
                    accountType,
                    optionalAccount.map(AccountRecord::email).orElse(null),
                    optionalAccount.map(AccountRecord::totpSecret).orElse(null),
                    optionalAccount.map(AccountRecord::registeredAt).orElse(Instant.now()),
                    optionalAccount.map(AccountRecord::lastLoginAt).orElse(null),
                    ipAddress
            );
            return storage.saveAccount(accountRecord).thenApply(ignored -> new RegisterResult(RegisterStatus.SUCCESS));
        });
    }

    @Override
    public CompletableFuture<ChangePasswordResult> changePassword(UUID uuid, String username, String oldPassword, String newPassword, String confirmation) {
        RegisterStatus validation = validateNewPassword(newPassword, confirmation);
        if (validation == RegisterStatus.PASSWORD_MISMATCH) {
            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.PASSWORD_MISMATCH));
        }
        if (validation == RegisterStatus.PASSWORD_TOO_SHORT) {
            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.PASSWORD_TOO_SHORT));
        }
        if (validation == RegisterStatus.PASSWORD_TOO_LONG) {
            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.PASSWORD_TOO_LONG));
        }
        if (validation == RegisterStatus.PASSWORD_INVALID) {
            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.PASSWORD_INVALID));
        }

        return findAccount(uuid, username).thenCompose(optionalAccount -> {
            if (optionalAccount.isEmpty() || optionalAccount.get().passwordHash() == null) {
                return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.NOT_REGISTERED));
            }

            AccountRecord account = optionalAccount.get();
            if (!HashUtil.matches(oldPassword, account.passwordHash())) {
                return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.WRONG_OLD_PASSWORD));
            }

            return storage.updatePassword(uuid, HashUtil.hash(newPassword))
                    .thenApply(ignored -> new ChangePasswordResult(ChangePasswordStatus.SUCCESS));
        });
    }

    @Override
    public CompletableFuture<Void> logout(UUID uuid) {
        markUnauthenticated(uuid);
        return sessionManager.invalidate(uuid);
    }

    @Override
    public CompletableFuture<AuthenticationResult> autoAuthenticate(UUID uuid, String username, AccountType accountType, AuthState authState, String ipAddress) {
        return storage.touchLogin(uuid, username, ipAddress, accountType)
                .thenCompose(ignored -> sessionManager.create(uuid, ipAddress))
                .thenApply(ignored -> {
                    bruteForceGuard.clearFailures(ipAddress);
                    pendingTotpChallenges.remove(uuid);
                    trackedPlayers.put(uuid, new AuthPlayer(uuid, username, accountType, authState));
                    return new AuthenticationResult(accountType, authState);
                });
    }

    @Override
    public CompletableFuture<TotpSubmissionResult> submitTotp(UUID uuid, String username, String code) {
        PendingTotpChallenge challenge = pendingTotpChallenges.get(uuid);
        if (challenge == null) {
            return CompletableFuture.completedFuture(new TotpSubmissionResult(TotpSubmissionStatus.NOT_REQUIRED, trackedAccountType(uuid, username)));
        }

        return storage.findTotpSecret(uuid)
                .thenCompose(optionalSecret -> {
                    if (optionalSecret.isEmpty()) {
                        pendingTotpChallenges.remove(uuid);
                        return CompletableFuture.completedFuture(new TotpSubmissionResult(TotpSubmissionStatus.NOT_REQUIRED, challenge.accountType()));
                    }

                    String decrypted = totpService.decrypt(optionalSecret.get());
                    if (!totpService.verify(decrypted, code)) {
                        return CompletableFuture.completedFuture(new TotpSubmissionResult(TotpSubmissionStatus.INVALID, challenge.accountType()));
                    }

                    pendingTotpChallenges.remove(uuid);
                    return autoAuthenticate(uuid, username, challenge.accountType(), AuthState.AUTHENTICATED, challenge.ipAddress())
                            .thenApply(authenticationResult -> new TotpSubmissionResult(TotpSubmissionStatus.SUCCESS, challenge.accountType()));
                });
    }

    @Override
    public CompletableFuture<TotpSetupResult> setupTotp(UUID uuid, String username) {
        if (!totpService.isEnabled()) {
            return CompletableFuture.completedFuture(new TotpSetupResult(TotpSetupStatus.DISABLED_IN_CONFIG, "", ""));
        }

        return findAccount(uuid, username).thenCompose(optionalAccount -> {
            if (optionalAccount.isEmpty()) {
                return CompletableFuture.completedFuture(new TotpSetupResult(TotpSetupStatus.NOT_REGISTERED, "", ""));
            }

            if (optionalAccount.get().totpSecret() != null) {
                return CompletableFuture.completedFuture(new TotpSetupResult(TotpSetupStatus.ALREADY_ENABLED, "", ""));
            }

            TotpService.SetupData setupData = totpService.generate(username);
            return storage.updateTotpSecret(uuid, totpService.encrypt(setupData.secret()))
                    .thenApply(ignored -> new TotpSetupResult(TotpSetupStatus.SUCCESS, setupData.secret(), setupData.uri()));
        });
    }

    @Override
    public CompletableFuture<TotpDisableResult> disableTotp(UUID uuid, String code) {
        return storage.findTotpSecret(uuid)
                .thenCompose(optionalSecret -> {
                    if (optionalSecret.isEmpty()) {
                        return CompletableFuture.completedFuture(new TotpDisableResult(TotpDisableStatus.NOT_ENABLED));
                    }

                    String decrypted = totpService.decrypt(optionalSecret.get());
                    if (!totpService.verify(decrypted, code)) {
                        return CompletableFuture.completedFuture(new TotpDisableResult(TotpDisableStatus.INVALID));
                    }

                    return storage.updateTotpSecret(uuid, null)
                            .thenApply(ignored -> new TotpDisableResult(TotpDisableStatus.SUCCESS));
                });
    }

    @Override
    public CompletableFuture<Optional<AccountRecord>> findAccountByUsername(String username) {
        return storage.findAccountByUsername(username);
    }

    @Override
    public CompletableFuture<Boolean> resetPassword(String username, String newPassword) {
        return storage.findAccountByUsername(username)
                .thenCompose(optionalAccount -> {
                    if (optionalAccount.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return storage.updatePassword(optionalAccount.get().uuid(), HashUtil.hash(newPassword))
                            .thenApply(ignored -> true);
                });
    }

    @Override
    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    private CompletableFuture<LoginResult> handleLogin(
            UUID uuid,
            String username,
            String password,
            String ipAddress,
            Optional<AccountRecord> optionalAccount
    ) {
        if (optionalAccount.isEmpty() || optionalAccount.get().passwordHash() == null) {
            return CompletableFuture.completedFuture(new LoginResult(
                    LoginStatus.NEEDS_REGISTER,
                    trackedAccountType(uuid, username),
                    0,
                    settings.security().maxAttempts(),
                    0
            ));
        }

        AccountRecord account = optionalAccount.get();
        if (!HashUtil.matches(password, account.passwordHash())) {
            return bruteForceGuard.recordFailure(ipAddress)
                    .thenApply(failure -> new LoginResult(
                            failure.locked() ? LoginStatus.LOCKED : LoginStatus.WRONG_PASSWORD,
                            account.accountType(),
                            failure.attempts(),
                            failure.maxAttempts(),
                            failure.remainingSeconds()
                    ));
        }

        bruteForceGuard.clearFailures(ipAddress);
        return storage.touchLogin(uuid, username, ipAddress, account.accountType())
                .thenCompose(ignored -> {
                    if (settings.security().totpEnabled() && account.totpSecret() != null) {
                        pendingTotpChallenges.put(uuid, new PendingTotpChallenge(account.accountType(), ipAddress));
                        return CompletableFuture.completedFuture(new LoginResult(
                                LoginStatus.TOTP_REQUIRED,
                                account.accountType(),
                                0,
                                settings.security().maxAttempts(),
                                0
                        ));
                    }

                    return autoAuthenticate(uuid, username, account.accountType(), AuthState.AUTHENTICATED, ipAddress)
                            .thenApply(authentication -> new LoginResult(
                                    LoginStatus.SUCCESS,
                                    account.accountType(),
                                    0,
                                    settings.security().maxAttempts(),
                                    0
                            ));
                });
    }

    private CompletableFuture<Optional<AccountRecord>> findAccount(UUID uuid, String username) {
        return storage.findAccountByUuid(uuid)
                .thenCompose(optionalAccount -> optionalAccount.isPresent()
                        ? CompletableFuture.completedFuture(optionalAccount)
                        : storage.findAccountByUsername(username));
    }

    private RegisterStatus validateNewPassword(String password, String confirmation) {
        if (!password.equals(confirmation)) {
            return RegisterStatus.PASSWORD_MISMATCH;
        }
        if (password.length() < settings.passwordPolicy().minLength()) {
            return RegisterStatus.PASSWORD_TOO_SHORT;
        }
        if (password.length() > settings.passwordPolicy().maxLength()) {
            return RegisterStatus.PASSWORD_TOO_LONG;
        }

        Optional<Pattern> pattern = settings.passwordPolicy().compiledPattern();
        if (pattern.isPresent() && !pattern.get().matcher(password).matches()) {
            return RegisterStatus.PASSWORD_INVALID;
        }
        return RegisterStatus.SUCCESS;
    }

    private AccountType trackedAccountType(UUID uuid, String username) {
        return trackedPlayers.getOrDefault(uuid, new AuthPlayer(uuid, username, AccountType.CRACKED, AuthState.UNAUTHENTICATED)).accountType();
    }

    private record PendingTotpChallenge(AccountType accountType, String ipAddress) {
    }
}
