package com.monkey.proudAuth.common.auth.impl;

import com.monkey.proudAuth.common.auth.AuthService;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.AuthPlayer;
import com.monkey.proudAuth.common.model.AuthState;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.security.BruteForceGuard;
import com.monkey.proudAuth.common.security.TotpService;
import com.monkey.proudAuth.common.session.SessionManager;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.common.util.HashUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class AuthServiceImpl implements AuthService {

    private final StorageProvider storage;
    private final SessionManager sessionManager;
    private final BruteForceGuard bruteForceGuard;
    private final PremiumVerifier premiumVerifier;
    private final TotpService totpService;
    private final Map<UUID, AuthPlayer> trackedPlayers;
    private final Map<UUID, UUID> accountUuidsByRuntimeUuid;
    private final Map<UUID, PendingTotpChallenge> pendingTotpChallenges;
    private final Map<UUID, PendingTotpSetup> pendingTotpSetups;
    private volatile ProudAuthSettings settings;

    public AuthServiceImpl(
            StorageProvider storage,
            SessionManager sessionManager,
            BruteForceGuard bruteForceGuard,
            PremiumVerifier premiumVerifier,
            TotpService totpService,
            ProudAuthSettings settings
    ) {
        this.storage = storage;
        this.sessionManager = sessionManager;
        this.bruteForceGuard = bruteForceGuard;
        this.premiumVerifier = premiumVerifier;
        this.totpService = totpService;
        this.settings = settings;
        this.trackedPlayers = new ConcurrentHashMap<>();
        this.accountUuidsByRuntimeUuid = new ConcurrentHashMap<>();
        this.pendingTotpChallenges = new ConcurrentHashMap<>();
        this.pendingTotpSetups = new ConcurrentHashMap<>();
    }

    @Override
    public void trackPlayer(UUID uuid, UUID accountUuid, String username, AccountType accountType) {
        trackedPlayers.put(uuid, new AuthPlayer(uuid, username, accountType, AuthState.UNAUTHENTICATED));
        accountUuidsByRuntimeUuid.put(uuid, accountUuid);
    }

    @Override
    public void untrackPlayer(UUID uuid) {
        trackedPlayers.remove(uuid);
        accountUuidsByRuntimeUuid.remove(uuid);
        pendingTotpChallenges.remove(uuid);
        pendingTotpSetups.remove(uuid);
    }

    @Override
    public void markUnauthenticated(UUID uuid) {
        trackedPlayers.computeIfPresent(uuid, (ignored, current) -> current.withState(AuthState.UNAUTHENTICATED));
        pendingTotpChallenges.remove(uuid);
        pendingTotpSetups.remove(uuid);
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
            if (optionalAccount.map(AccountRecord::accountType).filter(type -> type == AccountType.PREMIUM).isPresent()) {
                return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.PREMIUM_ACCOUNT));
            }
            if (optionalAccount.isPresent() && optionalAccount.get().passwordHash() != null) {
                return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.ALREADY_REGISTERED));
            }

            return allowsCrackedClaimRegistration(username, ipAddress, optionalAccount)
                    .thenCompose(claimAllowsCracked -> {
                        if (claimAllowsCracked) {
                            return proceedWithRegistration(uuid, username, accountType, ipAddress, password, optionalAccount);
                        }
                        return premiumVerifier.verify(username)
                                .handle((premiumCheck, exception) -> {
                                    if (exception != null) {
                                        return new RegisterResult(RegisterStatus.MOJANG_UNAVAILABLE);
                                    }
                                    if (premiumCheck.premium()) {
                                        return new RegisterResult(RegisterStatus.PREMIUM_ACCOUNT);
                                    }
                                    return null;
                                })
                                .thenCompose(blockingResult -> blockingResult != null
                                        ? CompletableFuture.completedFuture(blockingResult)
                                        : proceedWithRegistration(uuid, username, accountType, ipAddress, password, optionalAccount));
                    });
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
            if (optionalAccount.map(AccountRecord::accountType).filter(type -> type == AccountType.PREMIUM).isPresent()) {
                return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.PREMIUM_ACCOUNT));
            }

            return allowsCrackedClaimPasswordFlow(username, optionalAccount)
                    .thenCompose(claimAllowsCracked -> claimAllowsCracked
                            ? CompletableFuture.completedFuture(null)
                            : premiumVerifier.verify(username)
                                    .handle((premiumCheck, exception) -> {
                                        if (exception != null) {
                                            return new ChangePasswordResult(ChangePasswordStatus.MOJANG_UNAVAILABLE);
                                        }
                                        if (premiumCheck.premium()) {
                                            return new ChangePasswordResult(ChangePasswordStatus.PREMIUM_ACCOUNT);
                                        }
                                        return null;
                                    }))
                    .thenCompose(blockingResult -> {
                        if (blockingResult != null) {
                            return CompletableFuture.completedFuture(blockingResult);
                        }
                        if (optionalAccount.isEmpty() || optionalAccount.get().passwordHash() == null) {
                            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.NOT_REGISTERED));
                        }

                        AccountRecord account = optionalAccount.get();
                        if (!HashUtil.matches(oldPassword, account.passwordHash())) {
                            return CompletableFuture.completedFuture(new ChangePasswordResult(ChangePasswordStatus.WRONG_OLD_PASSWORD));
                        }

                        return storage.updatePassword(account.uuid(), HashUtil.hash(newPassword))
                                .thenApply(ignored -> new ChangePasswordResult(ChangePasswordStatus.SUCCESS));
                    });
        });
    }

    @Override
    public CompletableFuture<Void> logout(UUID uuid) {
        markUnauthenticated(uuid);
        return sessionManager.invalidate(accountUuidsByRuntimeUuid.getOrDefault(uuid, uuid));
    }

    @Override
    public CompletableFuture<AuthenticationResult> autoAuthenticate(UUID uuid, String username, AccountType accountType, AuthState authState, String ipAddress) {
        return findAccount(uuid, username)
                .thenCompose(optionalAccount -> autoAuthenticate(
                        uuid,
                        resolvedAccountUuid(uuid, optionalAccount),
                        username,
                        accountType,
                        authState,
                        ipAddress
                ));
    }

    private CompletableFuture<AuthenticationResult> autoAuthenticate(
            UUID runtimeUuid,
            UUID accountUuid,
            String username,
            AccountType accountType,
            AuthState authState,
            String ipAddress
    ) {
        return storage.touchLogin(accountUuid, username, ipAddress, accountType)
                .thenCompose(ignored -> recordIpHistory(username, ipAddress, accountType))
                .thenCompose(ignored -> storage.deleteSessionsByUuidAndNotAccountType(accountUuid, accountType))
                .thenCompose(ignored -> sessionManager.create(accountUuid, ipAddress, accountType))
                .thenApply(ignored -> {
                    bruteForceGuard.clearFailures(ipAddress);
                    pendingTotpChallenges.remove(runtimeUuid);
                    trackedPlayers.put(runtimeUuid, new AuthPlayer(runtimeUuid, username, accountType, authState));
                    accountUuidsByRuntimeUuid.put(runtimeUuid, accountUuid);
                    return new AuthenticationResult(accountType, authState);
                });
    }

    @Override
    public CompletableFuture<TotpSubmissionResult> submitTotp(UUID uuid, String username, String code) {
        PendingTotpChallenge challenge = pendingTotpChallenges.get(uuid);
        if (challenge == null) {
            return CompletableFuture.completedFuture(new TotpSubmissionResult(TotpSubmissionStatus.NOT_REQUIRED, trackedAccountType(uuid, username)));
        }

        return storage.findTotpSecret(challenge.accountUuid())
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
                    return autoAuthenticate(uuid, challenge.accountUuid(), username, challenge.accountType(), challenge.authState(), challenge.ipAddress())
                            .thenApply(authenticationResult -> new TotpSubmissionResult(TotpSubmissionStatus.SUCCESS, challenge.accountType()));
                });
    }

    @Override
    public CompletableFuture<TotpSetupInitResult> initTotpSetup(UUID uuid, String username) {
        if (!totpService.isEnabled()) {
            return CompletableFuture.completedFuture(new TotpSetupInitResult(
                    TotpSetupInitStatus.DISABLED_IN_CONFIG,
                    "",
                    "",
                    "",
                    0L,
                    settings.security().totpSetupMaxAttempts()
            ));
        }

        return findAccount(uuid, username).thenCompose(optionalAccount -> {
            if (optionalAccount.isEmpty()) {
                return CompletableFuture.completedFuture(new TotpSetupInitResult(
                        TotpSetupInitStatus.NOT_REGISTERED,
                        "",
                        "",
                        "",
                        0L,
                        settings.security().totpSetupMaxAttempts()
                ));
            }

            if (optionalAccount.get().totpSecret() != null) {
                return CompletableFuture.completedFuture(new TotpSetupInitResult(
                        TotpSetupInitStatus.ALREADY_ENABLED,
                        "",
                        "",
                        "",
                        0L,
                        settings.security().totpSetupMaxAttempts()
                ));
            }

            TotpService.SetupData setupData = totpService.generate(username);
            Instant expiresAt = Instant.now().plusSeconds(settings.security().totpSetupTimeoutSeconds());
            int maxAttempts = settings.security().totpSetupMaxAttempts();
            PendingTotpSetup pendingTotpSetup = new PendingTotpSetup(
                    optionalAccount.get().uuid(),
                    setupData.secret(),
                    setupData.uri(),
                    buildQrCodeUrl(setupData.uri()),
                    expiresAt,
                    maxAttempts,
                    maxAttempts
            );
            pendingTotpSetups.put(uuid, pendingTotpSetup);
            return CompletableFuture.completedFuture(new TotpSetupInitResult(
                    TotpSetupInitStatus.SUCCESS,
                    pendingTotpSetup.secret(),
                    pendingTotpSetup.uri(),
                    pendingTotpSetup.qrCodeUrl(),
                    pendingTotpSetup.expiresAt().getEpochSecond(),
                    pendingTotpSetup.maxAttempts()
            ));
        });
    }

    @Override
    public CompletableFuture<TotpSetupConfirmResult> confirmTotpSetup(UUID uuid, String code) {
        PendingTotpSetup pendingSetup = pendingTotpSetups.get(uuid);
        if (pendingSetup == null) {
            return CompletableFuture.completedFuture(new TotpSetupConfirmResult(
                    TotpSetupConfirmStatus.SETUP_NOT_INITIALIZED,
                    0,
                    0L
            ));
        }
        if (Instant.now().isAfter(pendingSetup.expiresAt())) {
            pendingTotpSetups.remove(uuid);
            return CompletableFuture.completedFuture(new TotpSetupConfirmResult(
                    TotpSetupConfirmStatus.SETUP_EXPIRED,
                    0,
                    0L
            ));
        }
        if (!totpService.verify(pendingSetup.secret(), code)) {
            int remainingAttempts = Math.max(0, pendingSetup.remainingAttempts() - 1);
            if (remainingAttempts <= 0) {
                pendingTotpSetups.remove(uuid);
                return CompletableFuture.completedFuture(new TotpSetupConfirmResult(
                        TotpSetupConfirmStatus.SETUP_ATTEMPTS_EXHAUSTED,
                        0,
                        pendingSetup.expiresAt().getEpochSecond()
                ));
            }

            pendingTotpSetups.put(
                    uuid,
                    new PendingTotpSetup(
                            pendingSetup.accountUuid(),
                            pendingSetup.secret(),
                            pendingSetup.uri(),
                            pendingSetup.qrCodeUrl(),
                            pendingSetup.expiresAt(),
                            remainingAttempts,
                            pendingSetup.maxAttempts()
                    )
            );
            return CompletableFuture.completedFuture(new TotpSetupConfirmResult(
                    TotpSetupConfirmStatus.INVALID,
                    remainingAttempts,
                    pendingSetup.expiresAt().getEpochSecond()
            ));
        }

        return storage.updateTotpSecret(pendingSetup.accountUuid(), totpService.encrypt(pendingSetup.secret()))
                .thenCompose(ignored -> storage.updateTotpFlow(pendingSetup.accountUuid(), settings.security().totpDefaultFlowAlways()))
                .thenApply(ignored -> {
                    pendingTotpSetups.remove(uuid);
                    return new TotpSetupConfirmResult(
                            TotpSetupConfirmStatus.SUCCESS,
                            pendingSetup.remainingAttempts(),
                            pendingSetup.expiresAt().getEpochSecond()
                    );
                });
    }

    @Override
    public CompletableFuture<TotpDisableResult> disableTotp(UUID uuid, String code) {
        String username = trackedPlayers.getOrDefault(
                uuid,
                new AuthPlayer(uuid, "", AccountType.CRACKED, AuthState.UNAUTHENTICATED)
        ).username();
        return findAccount(uuid, username)
                .thenCompose(optionalAccount -> {
                    if (optionalAccount.isEmpty() || optionalAccount.get().totpSecret() == null) {
                        return CompletableFuture.completedFuture(new TotpDisableResult(TotpDisableStatus.NOT_ENABLED));
                    }

                    String decrypted = totpService.decrypt(optionalAccount.get().totpSecret());
                    if (!totpService.verify(decrypted, code)) {
                        return CompletableFuture.completedFuture(new TotpDisableResult(TotpDisableStatus.INVALID));
                    }

                    return storage.updateTotpSecret(optionalAccount.get().uuid(), null)
                            .thenApply(ignored -> {
                                pendingTotpSetups.remove(uuid);
                                pendingTotpChallenges.remove(uuid);
                                return new TotpDisableResult(TotpDisableStatus.SUCCESS);
                            });
                });
    }

    @Override
    public CompletableFuture<TotpFlowResult> updateTotpFlow(UUID uuid, String username, boolean alwaysRequired) {
        if (!totpService.isEnabled()) {
            return CompletableFuture.completedFuture(new TotpFlowResult(TotpFlowStatus.NOT_ENABLED, alwaysRequired));
        }
        return findAccount(uuid, username).thenCompose(optionalAccount -> {
            if (optionalAccount.isEmpty()) {
                return CompletableFuture.completedFuture(new TotpFlowResult(TotpFlowStatus.NOT_REGISTERED, alwaysRequired));
            }
            if (optionalAccount.get().totpSecret() == null) {
                return CompletableFuture.completedFuture(new TotpFlowResult(TotpFlowStatus.NOT_ENABLED, alwaysRequired));
            }
            return storage.updateTotpFlow(optionalAccount.get().uuid(), alwaysRequired)
                    .thenApply(ignored -> new TotpFlowResult(TotpFlowStatus.SUCCESS, alwaysRequired));
        });
    }

    @Override
    public CompletableFuture<TotpFlowResult> fetchTotpFlow(UUID uuid, String username) {
        return findAccount(uuid, username).thenApply(optionalAccount -> {
            if (optionalAccount.isEmpty()) {
                return new TotpFlowResult(TotpFlowStatus.NOT_REGISTERED, settings.security().totpDefaultFlowAlways());
            }
            if (optionalAccount.get().totpSecret() == null) {
                return new TotpFlowResult(TotpFlowStatus.NOT_ENABLED, optionalAccount.get().totpFlowAlways());
            }
            return new TotpFlowResult(TotpFlowStatus.SUCCESS, optionalAccount.get().totpFlowAlways());
        });
    }

    @Override
    public CompletableFuture<TotpChallengeResult> authenticateWithTotpGate(
            UUID uuid,
            String username,
            AccountType accountType,
            AuthState authState,
            String ipAddress
    ) {
        return findAccount(uuid, username)
                .thenCompose(optionalAccount -> {
                    String storedSecret = optionalAccount.map(AccountRecord::totpSecret).orElse(null);
                    boolean alwaysRequired = optionalAccount.map(AccountRecord::totpFlowAlways).orElse(settings.security().totpDefaultFlowAlways());
                    UUID accountUuid = resolvedAccountUuid(uuid, optionalAccount);
                    return completeAuthenticationWithTotpGate(
                            uuid,
                            accountUuid,
                            username,
                            accountType,
                            authState,
                            ipAddress,
                            storedSecret,
                            alwaysRequired
                    );
                });
    }

    @Override
    public boolean hasPendingTotpChallenge(UUID uuid) {
        return pendingTotpChallenges.containsKey(uuid);
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
                    if (optionalAccount.get().accountType() == AccountType.PREMIUM) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return storage.updatePassword(optionalAccount.get().uuid(), HashUtil.hash(newPassword))
                            .thenApply(ignored -> true);
                });
    }

    @Override
    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
        pendingTotpSetups.clear();
    }

    private CompletableFuture<LoginResult> handleLogin(
            UUID uuid,
            String username,
            String password,
            String ipAddress,
            Optional<AccountRecord> optionalAccount
    ) {
        if (optionalAccount.isEmpty()) {
            return CompletableFuture.completedFuture(new LoginResult(
                    LoginStatus.NEEDS_REGISTER,
                    trackedAccountType(uuid, username),
                    0,
                    settings.security().maxAttempts(),
                    0
            ));
        }

        AccountRecord account = optionalAccount.get();
        if (account.passwordHash() == null) {
            LoginStatus status = account.accountType() == AccountType.PREMIUM
                    ? LoginStatus.PREMIUM_ACCOUNT
                    : LoginStatus.NEEDS_REGISTER;
            return CompletableFuture.completedFuture(new LoginResult(
                    status,
                    account.accountType(),
                    0,
                    settings.security().maxAttempts(),
                    0
            ));
        }

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
        return completeAuthenticationWithTotpGate(
                uuid,
                account.uuid(),
                username,
                account.accountType(),
                AuthState.AUTHENTICATED,
                ipAddress,
                account.totpSecret(),
                account.totpFlowAlways()
        ).thenApply(result -> new LoginResult(
                result.status() == TotpChallengeStatus.TOTP_REQUIRED ? LoginStatus.TOTP_REQUIRED : LoginStatus.SUCCESS,
                account.accountType(),
                0,
                settings.security().maxAttempts(),
                0
        ));
    }

    private CompletableFuture<Optional<AccountRecord>> findAccount(UUID uuid, String username) {
        UUID mappedAccountUuid = accountUuidsByRuntimeUuid.get(uuid);
        CompletableFuture<Optional<AccountRecord>> byUuid = mappedAccountUuid != null && !mappedAccountUuid.equals(uuid)
                ? storage.findAccountByUuid(mappedAccountUuid)
                : storage.findAccountByUuid(uuid);

        return byUuid.thenCompose(optionalAccount -> {
            if (optionalAccount.isPresent()) {
                return CompletableFuture.completedFuture(optionalAccount);
            }
            if (mappedAccountUuid != null && !mappedAccountUuid.equals(uuid)) {
                return storage.findAccountByUuid(uuid).thenCompose(runtimeAccount -> runtimeAccount.isPresent()
                        ? CompletableFuture.completedFuture(runtimeAccount)
                        : lookupAccountByUsername(username));
            }
            return lookupAccountByUsername(username);
        });
    }

    private CompletableFuture<RegisterResult> proceedWithRegistration(
            UUID uuid,
            String username,
            AccountType accountType,
            String ipAddress,
            String password,
            Optional<AccountRecord> optionalAccount
    ) {
        UUID targetUuid = optionalAccount.map(AccountRecord::uuid).orElse(uuid);
        String targetUsername = optionalAccount.map(AccountRecord::username).orElse(username);
        AccountType targetAccountType = optionalAccount
                .map(AccountRecord::accountType)
                .orElse(accountType);

        AccountRecord accountRecord = new AccountRecord(
                targetUuid,
                targetUsername,
                HashUtil.hash(password),
                targetAccountType,
                optionalAccount.map(AccountRecord::email).orElse(null),
                optionalAccount.map(AccountRecord::totpSecret).orElse(null),
                optionalAccount.map(AccountRecord::totpFlowAlways).orElse(settings.security().totpDefaultFlowAlways()),
                optionalAccount.map(AccountRecord::registeredAt).orElse(Instant.now()),
                optionalAccount.map(AccountRecord::lastLoginAt).orElse(null),
                ipAddress
        );
        return storage.saveAccount(accountRecord)
                .thenCompose(ignored -> recordIpHistory(targetUsername, ipAddress, targetAccountType))
                .thenApply(ignored -> new RegisterResult(RegisterStatus.SUCCESS));
    }

    private CompletableFuture<Boolean> allowsCrackedClaimRegistration(
            String username,
            String ipAddress,
            Optional<AccountRecord> optionalAccount
    ) {
        if (!usesClaimOnFirstJoin()) {
            return CompletableFuture.completedFuture(false);
        }
        if (optionalAccount.map(AccountRecord::accountType).filter(type -> type == AccountType.CRACKED).isPresent()) {
            return CompletableFuture.completedFuture(true);
        }
        return storage.findIdentityClaim(username)
                .thenCompose(optionalClaim -> {
                    if (optionalClaim.isPresent()) {
                        return CompletableFuture.completedFuture(optionalClaim.get().claimType() == AccountType.CRACKED);
                    }
                    return storage.findPendingIdentityClaim(username, ipAddress)
                            .thenApply(optionalPending -> optionalPending
                                    .map(pending -> pending.claimType() == AccountType.CRACKED)
                                    .orElse(false));
                });
    }

    private CompletableFuture<Boolean> allowsCrackedClaimPasswordFlow(
            String username,
            Optional<AccountRecord> optionalAccount
    ) {
        if (!usesClaimOnFirstJoin()) {
            return CompletableFuture.completedFuture(false);
        }
        if (optionalAccount.map(AccountRecord::accountType).filter(type -> type == AccountType.CRACKED).isPresent()) {
            return CompletableFuture.completedFuture(true);
        }
        return storage.findIdentityClaim(username)
                .thenApply(optionalClaim -> optionalClaim
                        .map(claim -> claim.claimType() == AccountType.CRACKED)
                        .orElse(false));
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

    private CompletableFuture<TotpChallengeResult> completeAuthenticationWithTotpGate(
            UUID runtimeUuid,
            UUID accountUuid,
            String username,
            AccountType accountType,
            AuthState authState,
            String ipAddress,
            String totpSecret,
            boolean alwaysRequired
    ) {
        if (!settings.security().totpEnabled() || totpSecret == null) {
            return autoAuthenticate(runtimeUuid, accountUuid, username, accountType, authState, ipAddress)
                    .thenApply(authenticationResult -> new TotpChallengeResult(TotpChallengeStatus.AUTHENTICATED, false));
        }
        if (alwaysRequired) {
            pendingTotpChallenges.put(runtimeUuid, new PendingTotpChallenge(accountUuid, accountType, ipAddress, authState));
            trackedPlayers.computeIfPresent(runtimeUuid, (ignored, authPlayer) -> authPlayer.withState(AuthState.UNAUTHENTICATED));
            return CompletableFuture.completedFuture(new TotpChallengeResult(TotpChallengeStatus.TOTP_REQUIRED, false));
        }

        return isSuspiciousAccess(username, ipAddress).thenCompose(suspicious -> {
            if (suspicious) {
                pendingTotpChallenges.put(runtimeUuid, new PendingTotpChallenge(accountUuid, accountType, ipAddress, authState));
                trackedPlayers.computeIfPresent(runtimeUuid, (ignored, authPlayer) -> authPlayer.withState(AuthState.UNAUTHENTICATED));
                return CompletableFuture.completedFuture(new TotpChallengeResult(TotpChallengeStatus.TOTP_REQUIRED, true));
            }
            return autoAuthenticate(runtimeUuid, accountUuid, username, accountType, authState, ipAddress)
                    .thenApply(authenticationResult -> new TotpChallengeResult(TotpChallengeStatus.AUTHENTICATED, false));
        });
    }

    private UUID resolvedAccountUuid(UUID runtimeUuid, Optional<AccountRecord> optionalAccount) {
        return optionalAccount.map(AccountRecord::uuid)
                .orElse(accountUuidsByRuntimeUuid.getOrDefault(runtimeUuid, runtimeUuid));
    }

    private CompletableFuture<Optional<AccountRecord>> lookupAccountByUsername(String username) {
        if (username == null || username.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return storage.findAccountByUsername(username);
    }

    private CompletableFuture<Boolean> isSuspiciousAccess(String username, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return CompletableFuture.completedFuture(true);
        }

        Instant now = Instant.now();
        CompletableFuture<Integer> usernamesForIp1h = storage.countDistinctUsernamesForIpSince(
                ipAddress,
                now.minus(Duration.ofHours(1))
        );
        CompletableFuture<Integer> ipsForUsername24h = storage.countDistinctIpsForUsernameSince(
                canonicalUsername(username),
                now.minus(Duration.ofHours(24))
        );
        CompletableFuture<Boolean> ipBanned = settings.security().totpSuspiciousDenyWhenIpBanned()
                ? storage.findActiveBan(ipAddress).thenApply(Optional::isPresent)
                : CompletableFuture.completedFuture(false);

        return CompletableFuture.allOf(usernamesForIp1h, ipsForUsername24h, ipBanned)
                .thenApply(ignored -> {
                    if (ipBanned.join()) {
                        return true;
                    }
                    if (usernamesForIp1h.join() > settings.security().totpSuspiciousMaxUsernamesPerIp1h()) {
                        return true;
                    }
                    return ipsForUsername24h.join() > settings.security().totpSuspiciousMaxIpsPerUsername24h();
                })
                .exceptionally(ignored -> true);
    }

    private CompletableFuture<Void> recordIpHistory(String username, String ipAddress, AccountType accountType) {
        if (ipAddress == null || ipAddress.isBlank() || "unknown".equalsIgnoreCase(ipAddress)) {
            return CompletableFuture.completedFuture(null);
        }
        return storage.saveIpHistory(canonicalUsername(username), ipAddress, accountType, Instant.now());
    }

    private String canonicalUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private boolean usesClaimOnFirstJoin() {
        return settings.premium().usesLocalClaims()
                && settings.proxy().mode() == ProudAuthSettings.ProxyMode.VELOCITY;
    }

    private String buildQrCodeUrl(String otpAuthUri) {
        return "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + URLEncoder.encode(otpAuthUri, StandardCharsets.UTF_8);
    }

    private record PendingTotpChallenge(UUID accountUuid, AccountType accountType, String ipAddress, AuthState authState) {
    }

    private record PendingTotpSetup(
            UUID accountUuid,
            String secret,
            String uri,
            String qrCodeUrl,
            Instant expiresAt,
            int remainingAttempts,
            int maxAttempts
    ) {
    }
}
