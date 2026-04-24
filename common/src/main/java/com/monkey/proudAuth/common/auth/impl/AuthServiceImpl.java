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
        this.pendingTotpChallenges = new ConcurrentHashMap<>();
        this.pendingTotpSetups = new ConcurrentHashMap<>();
    }

    @Override
    public void trackPlayer(UUID uuid, String username, AccountType accountType) {
        trackedPlayers.put(uuid, new AuthPlayer(uuid, username, accountType, AuthState.UNAUTHENTICATED));
    }

    @Override
    public void untrackPlayer(UUID uuid) {
        trackedPlayers.remove(uuid);
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

        return premiumVerifier.verify(username).thenCompose(premiumCheck -> {
            if (premiumCheck.premium()) {
                return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.PREMIUM_ACCOUNT));
            }

            return findAccount(uuid, username).thenCompose(optionalAccount -> {
                if (optionalAccount.isPresent() && optionalAccount.get().passwordHash() != null) {
                    return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.ALREADY_REGISTERED));
                }
                if (optionalAccount.map(AccountRecord::accountType).orElse(AccountType.CRACKED) == AccountType.PREMIUM) {
                    return CompletableFuture.completedFuture(new RegisterResult(RegisterStatus.PREMIUM_ACCOUNT));
                }

                UUID targetUuid = optionalAccount.map(AccountRecord::uuid).orElse(uuid);
                String targetUsername = optionalAccount.map(AccountRecord::username).orElse(username);
                AccountType targetAccountType = optionalAccount
                        .map(AccountRecord::accountType)
                        .map(existingType -> existingType == AccountType.PREMIUM ? AccountType.PREMIUM : accountType)
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
                .thenCompose(ignored -> recordIpHistory(username, ipAddress, accountType))
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
                    return autoAuthenticate(uuid, username, challenge.accountType(), challenge.authState(), challenge.ipAddress())
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

        return storage.updateTotpSecret(uuid, totpService.encrypt(pendingSetup.secret()))
                .thenCompose(ignored -> storage.updateTotpFlow(uuid, settings.security().totpDefaultFlowAlways()))
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
            return storage.updateTotpFlow(uuid, alwaysRequired)
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
                    return completeAuthenticationWithTotpGate(
                            uuid,
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

    private CompletableFuture<TotpChallengeResult> completeAuthenticationWithTotpGate(
            UUID uuid,
            String username,
            AccountType accountType,
            AuthState authState,
            String ipAddress,
            String totpSecret,
            boolean alwaysRequired
    ) {
        if (!settings.security().totpEnabled() || totpSecret == null) {
            return autoAuthenticate(uuid, username, accountType, authState, ipAddress)
                    .thenApply(authenticationResult -> new TotpChallengeResult(TotpChallengeStatus.AUTHENTICATED, false));
        }
        if (alwaysRequired) {
            pendingTotpChallenges.put(uuid, new PendingTotpChallenge(accountType, ipAddress, authState));
            trackedPlayers.computeIfPresent(uuid, (ignored, authPlayer) -> authPlayer.withState(AuthState.UNAUTHENTICATED));
            return CompletableFuture.completedFuture(new TotpChallengeResult(TotpChallengeStatus.TOTP_REQUIRED, false));
        }

        return isSuspiciousAccess(username, ipAddress).thenCompose(suspicious -> {
            if (suspicious) {
                pendingTotpChallenges.put(uuid, new PendingTotpChallenge(accountType, ipAddress, authState));
                trackedPlayers.computeIfPresent(uuid, (ignored, authPlayer) -> authPlayer.withState(AuthState.UNAUTHENTICATED));
                return CompletableFuture.completedFuture(new TotpChallengeResult(TotpChallengeStatus.TOTP_REQUIRED, true));
            }
            return autoAuthenticate(uuid, username, accountType, authState, ipAddress)
                    .thenApply(authenticationResult -> new TotpChallengeResult(TotpChallengeStatus.AUTHENTICATED, false));
        });
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

    private String buildQrCodeUrl(String otpAuthUri) {
        return "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data="
                + URLEncoder.encode(otpAuthUri, StandardCharsets.UTF_8);
    }

    private record PendingTotpChallenge(AccountType accountType, String ipAddress, AuthState authState) {
    }

    private record PendingTotpSetup(
            String secret,
            String uri,
            String qrCodeUrl,
            Instant expiresAt,
            int remainingAttempts,
            int maxAttempts
    ) {
    }
}
