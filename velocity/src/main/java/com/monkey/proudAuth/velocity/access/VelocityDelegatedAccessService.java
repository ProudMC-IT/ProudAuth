package com.monkey.proudAuth.velocity.access;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.premium.PremiumVerifier;
import com.monkey.proudAuth.common.storage.*;
import com.monkey.proudAuth.common.util.HashUtil;
import com.monkey.proudAuth.velocity.session.VelocityResolvedPlayerStore;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.crypto.IdentifiedKey;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import com.velocitypowered.api.util.GameProfile;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VelocityDelegatedAccessService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 3;
    private static final int BACKEND_REFRESH_CONNECT_ATTEMPTS = 5;
    private static final int BACKEND_REFRESH_INITIAL_DELAY_MS = 800;
    private static final int BACKEND_REFRESH_RETRY_DELAY_MS = 500;
    private static final Pattern TEXTURES_PROPERTY_PATTERN = Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"textures\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"signature\"\\s*:\\s*\"([^\"]+)\"\\s*}"
    );

    private final Object pluginOwner;
    private final ProxyServer proxyServer;
    private final Supplier<StorageProvider> storageSupplier;
    private final Supplier<PremiumVerifier> premiumVerifierSupplier;
    private final VelocityResolvedPlayerStore resolvedPlayerStore;
    private final Supplier<ProudAuthNetworkConfig.Routing> routingSupplier;
    private final Supplier<String> regionIdSupplier;
    private final Supplier<ProudAuthNetworkConfig.PremiumTargets> premiumTargetsSupplier;
    private final Map<UUID, PendingChallenge> pendingChallenges = new ConcurrentHashMap<>();

    public VelocityDelegatedAccessService(
            Object pluginOwner,
            ProxyServer proxyServer,
            Supplier<StorageProvider> storageSupplier,
            Supplier<PremiumVerifier> premiumVerifierSupplier,
            VelocityResolvedPlayerStore resolvedPlayerStore,
            Supplier<ProudAuthNetworkConfig.Routing> routingSupplier,
            Supplier<String> regionIdSupplier,
            Supplier<ProudAuthNetworkConfig.PremiumTargets> premiumTargetsSupplier
    ) {
        this.pluginOwner = pluginOwner;
        this.proxyServer = proxyServer;
        this.storageSupplier = storageSupplier;
        this.premiumVerifierSupplier = premiumVerifierSupplier;
        this.resolvedPlayerStore = resolvedPlayerStore;
        this.routingSupplier = routingSupplier;
        this.regionIdSupplier = regionIdSupplier;
        this.premiumTargetsSupplier = premiumTargetsSupplier;
    }

    public CompletableFuture<AllowResult> allowAccess(Player owner, String delegateUsername) {
        return allowAccess(owner, delegateUsername, "");
    }

    public CompletableFuture<AllowResult> allowAccess(Player owner, String delegateUsername, String expectedFingerprint) {
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> ownerProfile = resolvedPlayerStore.find(owner.getUsername());
        if (ownerProfile.isEmpty() || !ownerProfile.get().networkAuthenticated()) {
            return CompletableFuture.completedFuture(AllowResult.ownerNotAuthenticated());
        }
        if (expectedFingerprint == null || expectedFingerprint.isBlank()) {
            return CompletableFuture.completedFuture(AllowResult.fingerprintRequired());
        }
        StorageProvider storage = storageSupplier.get();
        return storage.findAccountByUsername(owner.getUsername())
                .thenCompose(ownerAccount -> {
                    if (ownerAccount.isEmpty()) {
                        return CompletableFuture.completedFuture(AllowResult.ownerMissing());
                    }
                    return resolveRegisteredDelegate(delegateUsername)
                            .thenCompose(delegate -> {
                                if (delegate.isEmpty()) {
                                    return CompletableFuture.completedFuture(AllowResult.delegateNotRegistered());
                                }
                                DelegatedAccessIdentity identity = delegate.get();
                                if (!fingerprint(identity).equals(normalizeFingerprint(expectedFingerprint))) {
                                    return CompletableFuture.completedFuture(AllowResult.fingerprintMismatch(fingerprint(identity)));
                                }
                                String code = code();
                                Instant now = Instant.now();
                                DelegatedAccessGrantRecord grant = new DelegatedAccessGrantRecord(
                                        ownerAccount.get().uuid(),
                                        ownerAccount.get().username(),
                                        identity.uuid(),
                                        identity.username(),
                                        identity.accountType(),
                                        HashUtil.hash(code),
                                        code,
                                        now,
                                        now,
                                        true
                                );
                                return storage.saveDelegatedAccessGrant(grant)
                                        .thenApply(ignored -> {
                                            audit(owner, ownerAccount.get(), identity, "ALLOW_CREATED", "SUCCESS", "");
                                            return AllowResult.allowed(identity.username(), code, fingerprint(identity));
                                        });
                            });
                });
    }

    public Optional<String> fingerprint(Player player) {
        return resolvedPlayerStore.find(player.getUsername())
                .map(profile -> fingerprint(new DelegatedAccessIdentity(
                        profile.accountUuid(),
                        profile.accountName(),
                        profile.accountType()
                )));
    }

    public boolean isAuthenticated(Player player) {
        return resolvedPlayerStore.find(player.getUsername())
                .map(VelocityResolvedPlayerStore.ResolvedPlayer::networkAuthenticated)
                .orElse(false);
    }

    public CompletableFuture<HistoryPageResult> history(String username, DelegatedAccessHistoryDirection direction, int page, int pageSize) {
        StorageProvider storage = storageSupplier.get();
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(50, pageSize));
        return storage.findAccountByUsername(username)
                .thenCompose(account -> {
                    if (account.isEmpty()) {
                        return CompletableFuture.completedFuture(HistoryPageResult.missing());
                    }
                    AccountRecord record = account.get();
                    CompletableFuture<Integer> countFuture = storage.countDelegatedAccessHistory(record.uuid(), direction);
                    CompletableFuture<java.util.List<DelegatedAccessHistoryRecord>> rowsFuture =
                            storage.listDelegatedAccessHistory(record.uuid(), direction, safePage, safePageSize);
                    return countFuture.thenCombine(rowsFuture, (count, rows) -> HistoryPageResult.found(record, rows, count, safePage, safePageSize));
                });
    }

    public CompletableFuture<AccessListResult> listAllowedDelegates(Player owner) {
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> ownerProfile = resolvedPlayerStore.find(owner.getUsername());
        if (ownerProfile.isEmpty()) {
            return CompletableFuture.completedFuture(AccessListResult.missing());
        }
        return storageSupplier.get().listDelegatedAccessGrants(ownerProfile.get().accountUuid())
                .thenApply(AccessListResult::found);
    }

    public CompletableFuture<List<String>> listJoinableOwnerNames(Player delegate) {
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> delegateProfile = resolvedPlayerStore.find(delegate.getUsername());
        if (delegateProfile.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return storageSupplier.get().listDelegatedAccessGrantsForDelegate(delegateProfile.get().accountUuid())
                .thenApply(grants -> grants.stream()
                        .map(DelegatedAccessGrantRecord::ownerUsername)
                        .filter(username -> username != null && !username.isBlank())
                        .distinct()
                        .toList());
    }

    public CompletableFuture<JoinRequestResult> requestJoin(Player actor, String ownerUsername) {
        StorageProvider storage = storageSupplier.get();
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> actorProfile = resolvedPlayerStore.find(actor.getUsername());
        if (actorProfile.isEmpty()) {
            return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.ACTOR_UNRESOLVED));
        }
        if (!actorProfile.get().networkAuthenticated()) {
            return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.ACTOR_NOT_AUTHENTICATED));
        }
        if (actorProfile.get().isImpersonating()) {
            return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.ALREADY_IMPERSONATING));
        }

        return storage.findAccountByUsername(ownerUsername)
                .thenCompose(ownerAccount -> {
                    if (ownerAccount.isEmpty()) {
                        return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.OWNER_MISSING));
                    }
                    AccountRecord owner = ownerAccount.get();
                    if (owner.username().equalsIgnoreCase(actor.getUsername())) {
                        audit(actor, actorProfile.get(), owner, "JOIN_REQUEST", "DENIED", "SELF_TARGET");
                        return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.SELF_TARGET));
                    }
                    if (proxyServer.getPlayer(owner.username()).isPresent()) {
                        audit(actor, actorProfile.get(), owner, "JOIN_REQUEST", "DENIED", "OWNER_ONLINE");
                        return CompletableFuture.completedFuture(JoinRequestResult.error(JoinRequestStatus.OWNER_ONLINE));
                    }
                    return storage.findDelegatedAccessGrant(owner.uuid(), actorProfile.get().accountUuid())
                            .thenApply(grant -> {
                                if (grant.isEmpty()) {
                                    audit(actor, actorProfile.get(), owner, "JOIN_REQUEST", "DENIED", "GRANT_MISSING");
                                    return JoinRequestResult.error(JoinRequestStatus.GRANT_MISSING);
                                }
                                audit(actor, actorProfile.get(), owner, "JOIN_REQUEST", "PENDING", "");
                                pendingChallenges.put(actor.getUniqueId(), new PendingChallenge(grant.get(), Instant.now().plusSeconds(90), 0));
                                return JoinRequestResult.challenge(owner.username());
                            });
                });
    }

    public CompleteResult submitCode(Player actor, String code) {
        PendingChallenge challenge = pendingChallenges.get(actor.getUniqueId());
        VelocityResolvedPlayerStore.ResolvedPlayer actorProfile = resolvedPlayerStore.find(actor.getUsername()).orElse(null);
        if (challenge == null || !challenge.expiresAt().isAfter(Instant.now())) {
            pendingChallenges.remove(actor.getUniqueId());
            audit(actor, actorProfile, challenge == null ? null : challenge.grant(), "CODE_SUBMIT", "DENIED", "NO_CHALLENGE");
            return CompleteResult.error(CompleteStatus.NO_CHALLENGE);
        }
        if (!code.matches("\\d{6}")) {
            audit(actor, actorProfile, challenge.grant(), "CODE_SUBMIT", "FAILED", "INVALID_FORMAT");
            return CompleteResult.error(CompleteStatus.INVALID_CODE);
        }
        if (!HashUtil.matches(code, challenge.grant().codeHash())) {
            int attempts = challenge.attempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                pendingChallenges.remove(actor.getUniqueId());
                audit(actor, actorProfile, challenge.grant(), "CODE_SUBMIT", "LOCKED", "TOO_MANY_ATTEMPTS");
                return CompleteResult.error(CompleteStatus.TOO_MANY_ATTEMPTS);
            }
            pendingChallenges.put(actor.getUniqueId(), new PendingChallenge(challenge.grant(), challenge.expiresAt(), attempts));
            audit(actor, actorProfile, challenge.grant(), "CODE_SUBMIT", "FAILED", "INVALID_CODE");
            return CompleteResult.error(CompleteStatus.INVALID_CODE);
        }

        pendingChallenges.remove(actor.getUniqueId());
        DelegatedAccessGrantRecord grant = challenge.grant();
        AccountRecord owner = storageSupplier.get().findAccountByUuid(grant.ownerUuid()).join().orElse(null);
        if (owner == null) {
            audit(actor, actorProfile, grant, "CODE_SUBMIT", "DENIED", "OWNER_MISSING");
            return CompleteResult.error(CompleteStatus.OWNER_MISSING);
        }
        boolean delegatedProfileKeyMismatch = isDelegatedProfileKeyMismatch(actor, owner);
        if (delegatedProfileKeyMismatch && owner.accountType() == AccountType.PREMIUM && !premiumTargetsSupplier.get().enabled()) {
            audit(actor, actorProfile, owner, "CODE_SUBMIT", "DENIED", "PROFILE_KEY_MISMATCH");
            return CompleteResult.error(CompleteStatus.PROFILE_KEY_MISMATCH);
        }
        List<GameProfile.Property> delegatedProperties = delegatedProfileKeyMismatch && owner.accountType() == AccountType.PREMIUM
                ? fetchSignedProfileProperties(owner.uuid())
                : List.of();
        if (delegatedProfileKeyMismatch && !setProudXDelegatedBackendProfile(actor, true, owner.uuid(), owner.username(), delegatedProperties)) {
            audit(actor, actorProfile, owner, "CODE_SUBMIT", "DENIED", "PROUDX_FORWARDING_REQUIRED");
            return CompleteResult.error(CompleteStatus.PROUDX_FORWARDING_REQUIRED);
        }
        resolvedPlayerStore.startImpersonation(actor.getUsername(), owner.uuid(), owner.username(), owner.accountType());
        resolvedPlayerStore.markNetworkAuthenticated(actor.getUsername(), true);
        audit(actor, actorProfile, owner, "CODE_SUBMIT", "SUCCESS", delegatedProfileKeyMismatch ? "DELEGATED_PROFILE_KEY_BYPASS" : "");
        refreshBackendIdentity(actor, true);
        return CompleteResult.started(owner.username());
    }

    public LeaveResult leave(Player actor) {
        VelocityResolvedPlayerStore.ResolvedPlayer profile = resolvedPlayerStore.find(actor.getUsername()).orElse(null);
        if (profile != null && profile.isImpersonating()) {
            audit(actor, profile, profile.impersonationTargetUuid(), profile.impersonationTargetName(), "LEAVE", "SUCCESS", "MANUAL");
        }
        boolean stopped = resolvedPlayerStore.stopImpersonation(actor.getUsername());
        pendingChallenges.remove(actor.getUniqueId());
        if (!stopped) {
            setProudXDelegatedBackendProfile(actor, false, null, "");
            return LeaveResult.notImpersonating();
        }
        setProudXDelegatedBackendProfile(actor, false, null, "");
        refreshBackendIdentity(actor, false);
        return LeaveResult.left();
    }

    public OwnerConflictResult handleOwnerLogin(
            Player owner,
            VelocityResolvedPlayerStore.ResolvedPlayer ownerProfile,
            ProudAuthNetworkConfig.OwnerConflictPolicy policy,
            Component delegateRestoreMessage,
            Component delegateKickMessage
    ) {
        if (ownerProfile == null || ownerProfile.isImpersonating()) {
            return OwnerConflictResult.none();
        }

        Optional<VelocityResolvedPlayerStore.ImpersonatingSession> activeSession =
                resolvedPlayerStore.findImpersonatingTarget(ownerProfile.accountUuid());
        if (activeSession.isEmpty() || activeSession.get().proxyUsername().equalsIgnoreCase(owner.getUsername())) {
            return OwnerConflictResult.none();
        }

        Optional<Player> delegate = proxyServer.getPlayer(activeSession.get().proxyUsername());
        if (delegate.isEmpty()) {
            return OwnerConflictResult.none();
        }

        Player delegatePlayer = delegate.get();
        VelocityResolvedPlayerStore.ResolvedPlayer delegateProfile = activeSession.get().profile();
        ProudAuthNetworkConfig.OwnerConflictPolicy effectivePolicy =
                policy == null ? ProudAuthNetworkConfig.OwnerConflictPolicy.OWNER_TAKES_OVER : policy;

        if (effectivePolicy == ProudAuthNetworkConfig.OwnerConflictPolicy.DENY_OWNER) {
            audit(delegatePlayer, delegateProfile, ownerProfile.accountUuid(), ownerProfile.accountName(), "OWNER_CONFLICT", "DENIED", "OWNER_BLOCKED");
            return OwnerConflictResult.ownerDenied(delegatePlayer.getUsername());
        }

        if (effectivePolicy == ProudAuthNetworkConfig.OwnerConflictPolicy.KICK_DELEGATE) {
            audit(delegatePlayer, delegateProfile, ownerProfile.accountUuid(), ownerProfile.accountName(), "LEAVE", "SUCCESS", "OWNER_JOINED_KICK");
            resolvedPlayerStore.stopImpersonation(delegatePlayer.getUsername());
            pendingChallenges.remove(delegatePlayer.getUniqueId());
            setProudXDelegatedBackendProfile(delegatePlayer, false, null, "");
            delegatePlayer.disconnect(delegateKickMessage);
            return OwnerConflictResult.delegateRemoved(delegatePlayer.getUsername());
        }

        forceLeaveForOwnerTakeover(delegatePlayer, delegateProfile, delegateRestoreMessage);
        return OwnerConflictResult.delegateRemoved(delegatePlayer.getUsername());
    }

    private void forceLeaveForOwnerTakeover(
            Player delegate,
            VelocityResolvedPlayerStore.ResolvedPlayer delegateProfile,
            Component delegateRestoreMessage
    ) {
        if (delegateProfile != null && delegateProfile.isImpersonating()) {
            audit(delegate, delegateProfile, delegateProfile.impersonationTargetUuid(), delegateProfile.impersonationTargetName(), "LEAVE", "SUCCESS", "OWNER_JOINED");
        }
        resolvedPlayerStore.stopImpersonation(delegate.getUsername());
        pendingChallenges.remove(delegate.getUniqueId());
        setProudXDelegatedBackendProfile(delegate, false, null, "");
        delegate.sendMessage(delegateRestoreMessage);
        refreshBackendIdentity(delegate, false);
    }

    public CompletableFuture<Boolean> revoke(Player owner, String delegateUsername) {
        Optional<VelocityResolvedPlayerStore.ResolvedPlayer> ownerProfile = resolvedPlayerStore.find(owner.getUsername());
        if (ownerProfile.isEmpty() || !ownerProfile.get().networkAuthenticated()) {
            return CompletableFuture.completedFuture(false);
        }
        StorageProvider storage = storageSupplier.get();
        return storage.findAccountByUsername(owner.getUsername())
                .thenCompose(ownerAccount -> {
                    if (ownerAccount.isEmpty()) {
                        return CompletableFuture.completedFuture(false);
                    }
                    return resolveRegisteredDelegate(delegateUsername)
                            .thenCompose(delegate -> delegate
                                    .map(identity -> storage.revokeDelegatedAccessGrant(ownerAccount.get().uuid(), identity.uuid())
                                            .thenApply(removed -> {
                                                if (removed) {
                                                    audit(owner, ownerAccount.get(), identity, "ALLOW_REVOKED", "SUCCESS", "");
                                                }
                                                return removed;
                                            }))
                                    .orElseGet(() -> CompletableFuture.completedFuture(false)));
                });
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onDisconnect(DisconnectEvent event) {
        VelocityResolvedPlayerStore.ResolvedPlayer profile = resolvedPlayerStore.find(event.getPlayer().getUsername()).orElse(null);
        if (profile == null || !profile.isImpersonating()) {
            setProudXDelegatedBackendProfile(event.getPlayer(), false, null, "");
            return;
        }
        audit(event.getPlayer(), profile, profile.impersonationTargetUuid(), profile.impersonationTargetName(), "LEAVE", "SUCCESS", "DISCONNECT");
        setProudXDelegatedBackendProfile(event.getPlayer(), false, null, "");
    }

    private CompletableFuture<Optional<DelegatedAccessIdentity>> resolveRegisteredDelegate(String username) {
        Optional<Player> onlinePlayer = proxyServer.getPlayer(username);
        if (onlinePlayer.isPresent()) {
            Optional<VelocityResolvedPlayerStore.ResolvedPlayer> profile = resolvedPlayerStore.find(onlinePlayer.get().getUsername());
            if (profile.isPresent()) {
                VelocityResolvedPlayerStore.ResolvedPlayer resolved = profile.get();
                return storageSupplier.get().findAccountByUuid(resolved.accountUuid())
                        .thenApply(account -> account.map(this::identityFromAccount));
            }
        }

        StorageProvider storage = storageSupplier.get();
        return storage.findAccountByUsername(username)
                .thenApply(account -> account.map(this::identityFromAccount));
    }

    private DelegatedAccessIdentity identityFromAccount(AccountRecord record) {
        return new DelegatedAccessIdentity(record.uuid(), record.username(), record.accountType());
    }

    private void refreshBackendIdentity(Player player, boolean enteringImpersonation) {
        ProudAuthNetworkConfig.Routing routing = routingSupplier.get();
        String currentServer = player.getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("");
        String finalServer = routing.hasPostAuthServer() ? routing.postAuthServer() : currentServer;
        if (!enteringImpersonation && !currentServer.isBlank()) {
            finalServer = currentServer;
        }

        if (currentServer.isBlank()) {
            connect(player, finalServer);
            return;
        }

        Optional<RegisteredServer> bounceServer = findBounceServer(currentServer, finalServer, routing);
        if (bounceServer.isEmpty()) {
            if (!finalServer.isBlank()) {
                connect(player, finalServer);
            }
            return;
        }

        RegisteredServer bounce = bounceServer.get();
        String bounceName = bounce.getServerInfo().getName();
        if (routing.hasAuthEntryServer() && routing.authEntryServer().equalsIgnoreCase(bounceName)) {
            resolvedPlayerStore.rememberAllowedAuthEntry(player.getUsername(), routing.authEntryServer());
        }
        player.createConnectionRequest(bounce).fireAndForget();

        String delayedFinalServer = finalServer;
        scheduleBackendRefreshConnect(player.getUsername(), delayedFinalServer, 1, BACKEND_REFRESH_INITIAL_DELAY_MS);
    }

    private void scheduleBackendRefreshConnect(String username, String finalServer, int attempt, int delayMs) {
        if (finalServer == null || finalServer.isBlank()) {
            return;
        }
        proxyServer.getScheduler()
                .buildTask(pluginOwner, () -> attemptBackendRefreshConnect(username, finalServer, attempt))
                .delay(delayMs, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void attemptBackendRefreshConnect(String username, String finalServer, int attempt) {
        Optional<Player> player = proxyServer.getPlayer(username);
        if (player.isEmpty()) {
            return;
        }

        String currentServer = player.get().getCurrentServer()
                .map(server -> server.getServerInfo().getName())
                .orElse("");
        if (finalServer.equalsIgnoreCase(currentServer)) {
            return;
        }

        if (!currentServer.isBlank()) {
            connect(player.get(), finalServer);
        }
        if (attempt < BACKEND_REFRESH_CONNECT_ATTEMPTS) {
            scheduleBackendRefreshConnect(username, finalServer, attempt + 1, BACKEND_REFRESH_RETRY_DELAY_MS);
        }
    }

    private boolean isDelegatedProfileKeyMismatch(Player actor, AccountRecord target) {
        IdentifiedKey key = actor.getIdentifiedKey();
        return key == null || !target.uuid().equals(key.getSignatureHolder());
    }

    private boolean setProudXDelegatedBackendProfile(Player player, boolean suppress, UUID profileUuid, String profileName) {
        return setProudXDelegatedBackendProfile(player, suppress, profileUuid, profileName, List.of());
    }

    private boolean setProudXDelegatedBackendProfile(
            Player player,
            boolean suppress,
            UUID profileUuid,
            String profileName,
            List<GameProfile.Property> properties
    ) {
        try {
            if (suppress && !isProudXBackendProfileKeySuppressionSupported(player)) {
                return false;
            }
            try {
                Method method = player.getClass().getMethod("setProudxDelegatedBackendProfile", boolean.class, UUID.class, String.class, List.class);
                method.invoke(player, suppress, profileUuid, profileName, properties);
            } catch (NoSuchMethodException ignored) {
                Method method = player.getClass().getMethod("setProudxDelegatedBackendProfile", boolean.class, UUID.class, String.class);
                method.invoke(player, suppress, profileUuid, profileName);
            }
            if (suppress) {
                applyOptionalProudXDelegatedRoutingScope(player, routingSupplier.get(), regionIdSupplier.get());
            } else {
                clearOptionalProudXDelegatedRoutingScope(player);
            }
            return true;
        } catch (NoSuchMethodException exception) {
            return !suppress;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return !suppress;
        }
    }

    private void applyOptionalProudXDelegatedRoutingScope(
            Player player,
            ProudAuthNetworkConfig.Routing routing,
            String regionId
    ) {
        try {
            Method method = player.getClass().getMethod(
                    "setProudxDelegatedBackendRoutingScope",
                    String.class,
                    Collection.class
            );
            method.invoke(player, normalizeRegionId(regionId), delegatedRoutingScopeServers(routing));
        } catch (NoSuchMethodException ignored) {
            // Older ProudX builds do not expose optional routing scope support.
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional hardening only: delegated access must continue to work without this hook.
        }
    }

    private void clearOptionalProudXDelegatedRoutingScope(Player player) {
        try {
            Method method = player.getClass().getMethod("clearProudxDelegatedBackendRoutingScope");
            method.invoke(player);
        } catch (NoSuchMethodException ignored) {
            try {
                Method fallbackMethod = player.getClass().getMethod(
                        "setProudxDelegatedBackendRoutingScope",
                        String.class,
                        Collection.class
                );
                fallbackMethod.invoke(player, "", List.of());
            } catch (NoSuchMethodException ignoredAgain) {
                // Older ProudX builds do not expose optional routing scope support.
            } catch (ReflectiveOperationException | RuntimeException ignoredAgain) {
                // Optional hardening only: delegated access must continue to work without this hook.
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional hardening only: delegated access must continue to work without this hook.
        }
    }

    private Collection<String> delegatedRoutingScopeServers(ProudAuthNetworkConfig.Routing routing) {
        if (routing == null) {
            return List.of();
        }

        Set<String> allowedServers = new LinkedHashSet<>();
        if (routing.hasAuthEntryServer()) {
            allowedServers.add(routing.authEntryServer());
        }
        if (routing.hasPostAuthServer()) {
            allowedServers.add(routing.postAuthServer());
        }
        return List.copyOf(allowedServers);
    }

    private List<GameProfile.Property> fetchSignedProfileProperties(UUID uuid) {
        String undashedUuid = uuid.toString().replace("-", "");
        HttpURLConnection connection = null;
        try {
            URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + undashedUuid + "?unsigned=false");
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestProperty("User-Agent", "ProudAuth-Velocity");
            if (connection.getResponseCode() != 200) {
                return List.of();
            }
            String body = new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = TEXTURES_PROPERTY_PATTERN.matcher(body);
            List<GameProfile.Property> properties = new ArrayList<>();
            while (matcher.find()) {
                properties.add(new GameProfile.Property("textures", matcher.group(1), matcher.group(2)));
            }
            return List.copyOf(properties);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean isProudXBackendProfileKeySuppressionSupported(Player player) {
        try {
            Method method = player.getClass().getMethod("isProudxBackendProfileKeySuppressionSupported");
            Object result = method.invoke(player);
            return result instanceof Boolean supported && supported;
        } catch (NoSuchMethodException exception) {
            return false;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private Optional<RegisteredServer> findBounceServer(String currentServer, String finalServer, ProudAuthNetworkConfig.Routing routing) {
        if (routing.hasAuthEntryServer()
                && !routing.authEntryServer().equalsIgnoreCase(currentServer)
                && !routing.authEntryServer().equalsIgnoreCase(finalServer)) {
            return proxyServer.getServer(routing.authEntryServer());
        }
        return proxyServer.getAllServers().stream()
                .filter(server -> !server.getServerInfo().getName().equalsIgnoreCase(currentServer))
                .filter(server -> finalServer == null || !server.getServerInfo().getName().equalsIgnoreCase(finalServer))
                .findFirst();
    }

    private void connect(Player player, String serverName) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        proxyServer.getServer(serverName).ifPresent(server -> player.createConnectionRequest(server).fireAndForget());
    }

    private String normalizeRegionId(String regionId) {
        if (regionId == null) {
            return "";
        }
        String trimmed = regionId.trim();
        return trimmed.isEmpty() ? "" : trimmed.toLowerCase(Locale.ROOT);
    }

    private void audit(Player actor, AccountRecord owner, DelegatedAccessIdentity delegate, String eventType, String result, String reason) {
        saveAudit(
                delegate.uuid(),
                delegate.username(),
                owner.uuid(),
                owner.username(),
                eventType,
                result,
                reason,
                ipAddress(actor)
        );
    }

    private void audit(Player actor, VelocityResolvedPlayerStore.ResolvedPlayer actorProfile, AccountRecord target, String eventType, String result, String reason) {
        saveAudit(
                actorProfile == null ? actor.getUniqueId() : actorProfile.accountUuid(),
                actorProfile == null ? actor.getUsername() : actorProfile.accountName(),
                target.uuid(),
                target.username(),
                eventType,
                result,
                reason,
                ipAddress(actor)
        );
    }

    private void audit(Player actor, VelocityResolvedPlayerStore.ResolvedPlayer actorProfile, DelegatedAccessGrantRecord grant, String eventType, String result, String reason) {
        saveAudit(
                actorProfile == null ? actor.getUniqueId() : actorProfile.accountUuid(),
                actorProfile == null ? actor.getUsername() : actorProfile.accountName(),
                grant == null ? null : grant.ownerUuid(),
                grant == null ? "" : grant.ownerUsername(),
                eventType,
                result,
                reason,
                ipAddress(actor)
        );
    }

    private void audit(Player actor, VelocityResolvedPlayerStore.ResolvedPlayer actorProfile, UUID targetUuid, String targetUsername, String eventType, String result, String reason) {
        saveAudit(
                actorProfile == null ? actor.getUniqueId() : actorProfile.accountUuid(),
                actorProfile == null ? actor.getUsername() : actorProfile.accountName(),
                targetUuid,
                targetUsername,
                eventType,
                result,
                reason,
                ipAddress(actor)
        );
    }

    private void saveAudit(UUID actorUuid, String actorUsername, UUID targetUuid, String targetUsername, String eventType, String result, String reason, String ipAddress) {
        storageSupplier.get().saveDelegatedAccessHistory(new DelegatedAccessHistoryRecord(
                0L,
                actorUuid,
                safeUsername(actorUsername),
                targetUuid,
                safeUsername(targetUsername),
                eventType,
                result,
                reason == null ? "" : reason,
                ipAddress,
                Instant.now()
        )).exceptionally(ignored -> null);
    }

    private String ipAddress(Player player) {
        if (player.getRemoteAddress() instanceof java.net.InetSocketAddress socketAddress && socketAddress.getAddress() != null) {
            return socketAddress.getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static String safeUsername(String username) {
        return username == null || username.isBlank() ? "unknown" : username;
    }

    private static String code() {
        return String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
    }

    private static String fingerprint(DelegatedAccessIdentity identity) {
        String payload = "ProudAuth delegated access v1|" + identity.uuid() + "|" + identity.accountType().name();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 12; index++) {
                builder.append(String.format(Locale.ROOT, "%02x", hash[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 non disponibile.", exception);
        }
    }

    private static String normalizeFingerprint(String fingerprint) {
        return fingerprint.replace("-", "").trim().toLowerCase(Locale.ROOT);
    }

    private record DelegatedAccessIdentity(UUID uuid, String username, AccountType accountType) {
    }

    private record PendingChallenge(DelegatedAccessGrantRecord grant, Instant expiresAt, int attempts) {
    }

    public enum JoinRequestStatus {
        CHALLENGE,
        ACTOR_UNRESOLVED,
        ACTOR_NOT_AUTHENTICATED,
        ALREADY_IMPERSONATING,
        OWNER_MISSING,
        OWNER_ONLINE,
        SELF_TARGET,
        GRANT_MISSING
    }

    public enum CompleteStatus {
        STARTED,
        NO_CHALLENGE,
        INVALID_CODE,
        TOO_MANY_ATTEMPTS,
        OWNER_MISSING,
        PROFILE_KEY_MISMATCH,
        PROUDX_FORWARDING_REQUIRED
    }

    public record AllowResult(boolean success, String delegateUsername, String code, String fingerprint, AllowStatus status) {
        private static AllowResult allowed(String delegateUsername, String code, String fingerprint) {
            return new AllowResult(true, delegateUsername, code, fingerprint, AllowStatus.ALLOWED);
        }

        private static AllowResult ownerMissing() {
            return new AllowResult(false, "", "", "", AllowStatus.OWNER_MISSING);
        }

        private static AllowResult ownerNotAuthenticated() {
            return new AllowResult(false, "", "", "", AllowStatus.OWNER_NOT_AUTHENTICATED);
        }

        private static AllowResult delegateMissing() {
            return new AllowResult(false, "", "", "", AllowStatus.DELEGATE_MISSING);
        }

        private static AllowResult delegateNotRegistered() {
            return new AllowResult(false, "", "", "", AllowStatus.DELEGATE_NOT_REGISTERED);
        }

        private static AllowResult fingerprintRequired() {
            return new AllowResult(false, "", "", "", AllowStatus.FINGERPRINT_REQUIRED);
        }

        private static AllowResult fingerprintMismatch(String fingerprint) {
            return new AllowResult(false, "", "", fingerprint, AllowStatus.FINGERPRINT_MISMATCH);
        }
    }

    public enum AllowStatus {
        ALLOWED,
        OWNER_NOT_AUTHENTICATED,
        OWNER_MISSING,
        DELEGATE_MISSING,
        DELEGATE_NOT_REGISTERED,
        FINGERPRINT_REQUIRED,
        FINGERPRINT_MISMATCH
    }

    public record JoinRequestResult(JoinRequestStatus status, String ownerUsername) {
        private static JoinRequestResult challenge(String ownerUsername) {
            return new JoinRequestResult(JoinRequestStatus.CHALLENGE, ownerUsername);
        }

        private static JoinRequestResult error(JoinRequestStatus status) {
            return new JoinRequestResult(status, "");
        }
    }

    public record CompleteResult(CompleteStatus status, String ownerUsername) {
        private static CompleteResult started(String ownerUsername) {
            return new CompleteResult(CompleteStatus.STARTED, ownerUsername);
        }

        private static CompleteResult error(CompleteStatus status) {
            return new CompleteResult(status, "");
        }
    }

    public record LeaveResult(boolean success) {
        private static LeaveResult left() {
            return new LeaveResult(true);
        }

        private static LeaveResult notImpersonating() {
            return new LeaveResult(false);
        }
    }

    public record OwnerConflictResult(boolean conflict, boolean ownerDenied, String delegateUsername) {
        private static OwnerConflictResult none() {
            return new OwnerConflictResult(false, false, "");
        }

        private static OwnerConflictResult ownerDenied(String delegateUsername) {
            return new OwnerConflictResult(true, true, delegateUsername);
        }

        private static OwnerConflictResult delegateRemoved(String delegateUsername) {
            return new OwnerConflictResult(true, false, delegateUsername);
        }
    }

    public record HistoryPageResult(
            boolean found,
            AccountRecord account,
            java.util.List<DelegatedAccessHistoryRecord> rows,
            int totalRows,
            int page,
            int pageSize
    ) {
        private static HistoryPageResult missing() {
            return new HistoryPageResult(false, null, java.util.List.of(), 0, 1, 1);
        }

        private static HistoryPageResult found(AccountRecord account, java.util.List<DelegatedAccessHistoryRecord> rows, int totalRows, int page, int pageSize) {
            return new HistoryPageResult(true, account, rows, totalRows, page, pageSize);
        }

        public int totalPages() {
            return Math.max(1, (int) Math.ceil((double) totalRows / (double) pageSize));
        }
    }

    public record AccessListResult(boolean found, java.util.List<DelegatedAccessGrantRecord> grants) {
        private static AccessListResult missing() {
            return new AccessListResult(false, java.util.List.of());
        }

        private static AccessListResult found(java.util.List<DelegatedAccessGrantRecord> grants) {
            return new AccessListResult(true, grants);
        }
    }
}
