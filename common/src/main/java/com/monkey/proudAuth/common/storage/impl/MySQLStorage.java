package com.monkey.proudAuth.common.storage.impl;

import com.monkey.proudAuth.common.bridge.BridgeJoinMode;
import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.storage.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MySQLStorage implements StorageProvider {

    private volatile ProudAuthSettings settings;
    private final ExecutorService ioExecutor;
    private volatile HikariDataSource dataSource;

    public MySQLStorage(ProudAuthSettings settings) {
        this.settings = settings;
        this.ioExecutor = Executors.newFixedThreadPool(
                Math.max(2, settings.database().poolSize()),
                new StorageThreadFactory()
        );
    }

    @Override
    public synchronized void init() {
        rebuildDataSource();
        migrateSchema();
    }

    @Override
    public synchronized void reload(ProudAuthSettings settings) {
        this.settings = settings;
        rebuildDataSource();
        migrateSchema();
    }

    @Override
    public CompletableFuture<Optional<AccountRecord>> findAccountByUuid(UUID uuid) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT uuid, username, password_hash, account_type, email, totp_secret, totp_flow_always, registered_at, last_login_at, last_ip
                    FROM pa_accounts
                    WHERE uuid = ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapAccount(resultSet));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<AccountRecord>> findAccountByUsername(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT uuid, username, password_hash, account_type, email, totp_secret, totp_flow_always, registered_at, last_login_at, last_ip
                    FROM pa_accounts
                    WHERE LOWER(username) = LOWER(?)
                    ORDER BY
                        CASE WHEN account_type = 'PREMIUM' THEN 0 ELSE 1 END,
                        CASE WHEN password_hash IS NULL THEN 1 ELSE 0 END,
                        last_login_at DESC,
                        registered_at ASC
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapAccount(resultSet));
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listAccountUsernames(String prefix, int limit) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username
                    FROM pa_accounts
                    WHERE LOWER(username) LIKE LOWER(?)
                    GROUP BY username
                    ORDER BY
                        MAX(last_login_at) IS NULL,
                        MAX(last_login_at) DESC,
                        MIN(registered_at) ASC,
                        username ASC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, (prefix == null ? "" : prefix) + "%");
                statement.setInt(2, Math.max(1, Math.min(100, limit)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> usernames = new ArrayList<>();
                    while (resultSet.next()) {
                        usernames.add(resultSet.getString("username"));
                    }
                    return usernames;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveAccount(AccountRecord accountRecord) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_accounts
                    (uuid, username, password_hash, account_type, email, totp_secret, totp_flow_always, registered_at, last_login_at, last_ip)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        password_hash = IFNULL(VALUES(password_hash), password_hash),
                        account_type = CASE
                            WHEN account_type = 'PREMIUM' THEN account_type
                            WHEN VALUES(account_type) = 'PREMIUM' THEN 'PREMIUM'
                            ELSE VALUES(account_type)
                        END,
                        email = IFNULL(VALUES(email), email),
                        totp_secret = IFNULL(VALUES(totp_secret), totp_secret),
                        totp_flow_always = VALUES(totp_flow_always),
                        last_login_at = IFNULL(VALUES(last_login_at), last_login_at),
                        last_ip = IFNULL(VALUES(last_ip), last_ip)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, accountRecord.uuid().toString());
                statement.setString(2, accountRecord.username());
                setNullableString(statement, 3, accountRecord.passwordHash());
                statement.setString(4, accountRecord.accountType().name());
                setNullableString(statement, 5, accountRecord.email());
                setNullableString(statement, 6, accountRecord.totpSecret());
                statement.setBoolean(7, accountRecord.totpFlowAlways());
                statement.setTimestamp(8, Timestamp.from(accountRecord.registeredAt()));
                setNullableTimestamp(statement, 9, accountRecord.lastLoginAt());
                setNullableString(statement, 10, accountRecord.lastIp());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> updatePassword(UUID uuid, String passwordHash) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE pa_accounts SET password_hash = ? WHERE uuid = ?")) {
                statement.setString(1, passwordHash);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> touchLogin(UUID uuid, String username, String ip, AccountType accountType) {
        return runAsync(() -> {
            try (Connection connection = connection()) {
                String updateByUuidSql = """
                        UPDATE pa_accounts
                        SET username = ?,
                            account_type = CASE
                                WHEN account_type = 'PREMIUM' OR ? = 'PREMIUM' THEN 'PREMIUM'
                                ELSE account_type
                            END,
                            last_login_at = CURRENT_TIMESTAMP,
                            last_ip = ?
                        WHERE uuid = ?
                        """;
                int updatedByUuid;
                try (PreparedStatement statement = connection.prepareStatement(updateByUuidSql)) {
                    statement.setString(1, username);
                    statement.setString(2, accountType.name());
                    statement.setString(3, ip);
                    statement.setString(4, uuid.toString());
                    updatedByUuid = statement.executeUpdate();
                }
                if (updatedByUuid > 0) {
                    return;
                }

                String updateByUsernameSql = """
                        UPDATE pa_accounts
                        SET uuid = CASE
                                WHEN account_type = 'PREMIUM' AND ? <> 'PREMIUM' THEN uuid
                                ELSE ?
                            END,
                            username = ?,
                            account_type = CASE
                                WHEN account_type = 'PREMIUM' OR ? = 'PREMIUM' THEN 'PREMIUM'
                                ELSE account_type
                            END,
                            last_login_at = CURRENT_TIMESTAMP,
                            last_ip = ?
                        WHERE LOWER(username) = LOWER(?)
                        ORDER BY
                            CASE WHEN account_type = 'PREMIUM' THEN 0 ELSE 1 END,
                            last_login_at DESC,
                            registered_at ASC
                        LIMIT 1
                        """;
                int updatedByUsername;
                try (PreparedStatement statement = connection.prepareStatement(updateByUsernameSql)) {
                    statement.setString(1, accountType.name());
                    statement.setString(2, uuid.toString());
                    statement.setString(3, username);
                    statement.setString(4, accountType.name());
                    statement.setString(5, ip);
                    statement.setString(6, username);
                    updatedByUsername = statement.executeUpdate();
                }
                if (updatedByUsername > 0) {
                    return;
                }

                String insertSql = """
                        INSERT INTO pa_accounts (uuid, username, password_hash, account_type, registered_at, last_login_at, last_ip)
                        VALUES (?, ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                        """;
                try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, username);
                    statement.setString(3, accountType.name());
                    statement.setString(4, ip);
                    statement.executeUpdate();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<IdentityClaimRecord>> findIdentityClaim(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, claim_type, claimed_ip, claimed_at, updated_at
                    FROM pa_identity_claims
                    WHERE username = ?
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new IdentityClaimRecord(
                            resultSet.getString("username"),
                            AccountType.valueOf(resultSet.getString("claim_type")),
                            resultSet.getString("claimed_ip"),
                            resultSet.getTimestamp("claimed_at").toInstant(),
                            resultSet.getTimestamp("updated_at").toInstant()
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteIdentityClaim(String username) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM pa_identity_claims WHERE username = ?")) {
                statement.setString(1, canonicalUsername(username));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveIdentityClaim(String username, AccountType claimType, String claimedIp, Instant claimedAt) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_identity_claims (username, claim_type, claimed_ip, claimed_at, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                        claim_type = VALUES(claim_type),
                        claimed_ip = VALUES(claimed_ip),
                        claimed_at = VALUES(claimed_at),
                        updated_at = CURRENT_TIMESTAMP
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, claimType.name());
                setNullableString(statement, 3, claimedIp);
                statement.setTimestamp(4, Timestamp.from(claimedAt));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<PendingIdentityClaimRecord>> findLatestPendingIdentityClaim(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, claim_type, ip, created_at, expires_at
                    FROM pa_pending_identity_claims
                    WHERE username = ? AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY created_at DESC
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PendingIdentityClaimRecord(
                            resultSet.getString("username"),
                            AccountType.valueOf(resultSet.getString("claim_type")),
                            resultSet.getString("ip"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<PendingIdentityClaimRecord>> findPendingIdentityClaim(String username, String ipAddress) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, claim_type, ip, created_at, expires_at
                    FROM pa_pending_identity_claims
                    WHERE username = ? AND ip = ? AND expires_at > CURRENT_TIMESTAMP
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, ipAddress);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new PendingIdentityClaimRecord(
                            resultSet.getString("username"),
                            AccountType.valueOf(resultSet.getString("claim_type")),
                            resultSet.getString("ip"),
                            resultSet.getTimestamp("created_at").toInstant(),
                            resultSet.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> savePendingIdentityClaim(
            String username,
            AccountType claimType,
            String ipAddress,
            Instant createdAt,
            Instant expiresAt
    ) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_pending_identity_claims (username, claim_type, ip, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        claim_type = VALUES(claim_type),
                        ip = VALUES(ip),
                        created_at = VALUES(created_at),
                        expires_at = VALUES(expires_at)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, claimType.name());
                statement.setString(3, ipAddress);
                statement.setTimestamp(4, Timestamp.from(createdAt));
                statement.setTimestamp(5, Timestamp.from(expiresAt));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deletePendingIdentityClaim(String username) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM pa_pending_identity_claims WHERE username = ?")) {
                statement.setString(1, canonicalUsername(username));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteExpiredPendingIdentityClaims() {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM pa_pending_identity_claims WHERE expires_at < CURRENT_TIMESTAMP")) {
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> setTrustedIp(String username, String ipAddress) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_trusted_ips (username, ip, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON DUPLICATE KEY UPDATE
                        ip = VALUES(ip),
                        updated_at = CURRENT_TIMESTAMP
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, ipAddress);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> findTrustedIp(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT ip
                    FROM pa_trusted_ips
                    WHERE username = ?
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(resultSet.getString("ip"));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> deleteTrustedIp(String username) {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM pa_trusted_ips WHERE username = ?")) {
                statement.setString(1, canonicalUsername(username));
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<Void> addPremiumIpWhitelist(String username, String ip) {
        return runAsync(() -> {
            String sql = """
                    INSERT IGNORE INTO pa_premium_ip_whitelist (username, ip)
                    VALUES (?, ?)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, ip);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> clearPremiumIpWhitelist(String username) {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "DELETE FROM pa_premium_ip_whitelist WHERE username = ?")) {
                statement.setString(1, canonicalUsername(username));
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removePremiumIpWhitelist(String username, String ip) {
        return supplyAsync(() -> {
            String sql = """
                    DELETE FROM pa_premium_ip_whitelist
                    WHERE username = ? AND ip = ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, ip);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listPremiumIpWhitelist(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT ip
                    FROM pa_premium_ip_whitelist
                    WHERE username = ?
                    ORDER BY added_at
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> ips = new ArrayList<>();
                    while (resultSet.next()) {
                        ips.add(resultSet.getString("ip"));
                    }
                    return ips;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> isInPremiumIpWhitelist(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT COUNT(*)
                    FROM pa_premium_ip_whitelist
                    WHERE username = ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1) > 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> isPremiumIpWhitelisted(String username, String ip) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT COUNT(*)
                    FROM pa_premium_ip_whitelist
                    WHERE username = ? AND ip = ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setString(2, ip);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1) > 0;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateTotpSecret(UUID uuid, @Nullable String encryptedSecret) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE pa_accounts SET totp_secret = ? WHERE uuid = ?")) {
                setNullableString(statement, 1, encryptedSecret);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> findTotpSecret(UUID uuid) {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("SELECT totp_secret FROM pa_accounts WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.ofNullable(resultSet.getString("totp_secret"));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateTotpFlow(UUID uuid, boolean alwaysRequired) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE pa_accounts SET totp_flow_always = ? WHERE uuid = ?")) {
                statement.setBoolean(1, alwaysRequired);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Session>> findValidSession(UUID uuid, @Nullable String ip, AccountType expectedAccountType) {
        return supplyAsync(() -> {
            String sql = ip == null
                    ? "SELECT token, uuid, ip, account_type, expires_at FROM pa_sessions WHERE uuid = ? AND account_type = ? AND expires_at > CURRENT_TIMESTAMP LIMIT 1"
                    : "SELECT token, uuid, ip, account_type, expires_at FROM pa_sessions WHERE uuid = ? AND ip = ? AND account_type = ? AND expires_at > CURRENT_TIMESTAMP LIMIT 1";
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                if (ip != null) {
                    statement.setString(2, ip);
                    statement.setString(3, expectedAccountType.name());
                } else {
                    statement.setString(2, expectedAccountType.name());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapSession(resultSet));
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<Session>> listSessions(UUID uuid, boolean onlyActive) {
        return supplyAsync(() -> {
            String sql = onlyActive
                    ? "SELECT token, uuid, ip, account_type, expires_at FROM pa_sessions WHERE uuid = ? AND expires_at > CURRENT_TIMESTAMP ORDER BY expires_at DESC"
                    : "SELECT token, uuid, ip, account_type, expires_at FROM pa_sessions WHERE uuid = ? ORDER BY expires_at DESC";
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<Session> sessions = new ArrayList<>();
                    while (resultSet.next()) {
                        sessions.add(mapSession(resultSet));
                    }
                    return sessions;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveSession(Session session) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_sessions (token, uuid, ip, account_type, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        uuid = VALUES(uuid),
                        ip = VALUES(ip),
                        account_type = VALUES(account_type),
                        expires_at = VALUES(expires_at)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, session.token());
                statement.setString(2, session.uuid().toString());
                statement.setString(3, session.ip());
                statement.setString(4, session.accountType().name());
                statement.setTimestamp(5, Timestamp.from(session.expiresAt()));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteSessionsByIp(String ip) {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_sessions WHERE ip = ?")) {
                statement.setString(1, ip);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteSessions(UUID uuid) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_sessions WHERE uuid = ?")) {
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteSessionsByUuidAndNotAccountType(UUID uuid, AccountType keepType) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_sessions WHERE uuid = ? AND account_type <> ?")) {
                statement.setString(1, uuid.toString());
                statement.setString(2, keepType.name());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteSession(String token) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_sessions WHERE token = ?")) {
                statement.setString(1, token);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteExpiredSessions() {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_sessions WHERE expires_at < CURRENT_TIMESTAMP")) {
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> createBackendJoinProbe(
            String probeId,
            String username,
            String ipAddress,
            String targetServer,
            Instant issuedAt,
            Instant expiresAt
    ) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_backend_join_probes
                    (probe_id, username, ip, target_server, issued_at, expires_at, acknowledged_at, responder_id)
                    VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        ip = VALUES(ip),
                        target_server = VALUES(target_server),
                        issued_at = VALUES(issued_at),
                        expires_at = VALUES(expires_at),
                        acknowledged_at = NULL,
                        responder_id = NULL
                    """;
            try (Connection connection = connection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, probeId);
                statement.setString(2, username);
                statement.setString(3, ipAddress);
                statement.setString(4, targetServer);
                statement.setTimestamp(5, Timestamp.from(issuedAt));
                statement.setTimestamp(6, Timestamp.from(expiresAt));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> isBackendJoinProbeAcknowledged(String probeId) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT acknowledged_at IS NOT NULL AS acknowledged
                    FROM pa_backend_join_probes
                    WHERE probe_id = ? AND expires_at > CURRENT_TIMESTAMP
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, probeId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return false;
                    }
                    return resultSet.getBoolean("acknowledged");
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> acknowledgePendingBackendJoinProbes(String responderId, String serverId, int maxBatchSize) {
        return supplyAsync(() -> {
            int boundedBatchSize = Math.max(1, maxBatchSize);
            String sql = """
                    UPDATE pa_backend_join_probes
                    SET acknowledged_at = CURRENT_TIMESTAMP,
                        responder_id = ?
                    WHERE acknowledged_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP
                      AND target_server = ?
                    ORDER BY issued_at ASC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, responderId);
                statement.setString(2, serverId);
                statement.setInt(3, boundedBatchSize);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteBackendJoinProbe(String probeId) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_backend_join_probes WHERE probe_id = ?")) {
                statement.setString(1, probeId);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteExpiredBackendJoinProbes() {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_backend_join_probes WHERE expires_at < CURRENT_TIMESTAMP")) {
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveIpHistory(String username, String ipAddress, AccountType accountType, Instant observedAt) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_ip_history (username, ip, account_type, observed_at)
                    VALUES (?, ?, ?, ?)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, ipAddress);
                statement.setString(3, accountType.name());
                statement.setTimestamp(4, Timestamp.from(observedAt));
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countDistinctUsernamesForIpSince(String ipAddress, Instant since) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT COUNT(DISTINCT username)
                    FROM pa_ip_history
                    WHERE ip = ? AND observed_at >= ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ipAddress);
                statement.setTimestamp(2, Timestamp.from(since));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> countDistinctIpsForUsernameSince(String username, Instant since) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT COUNT(DISTINCT ip)
                    FROM pa_ip_history
                    WHERE username = ? AND observed_at >= ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setTimestamp(2, Timestamp.from(since));
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getInt(1);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteIpHistoryOlderThan(Instant cutoff) {
        return supplyAsync(() -> {
            String sql = "DELETE FROM pa_ip_history WHERE observed_at < ?";
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(cutoff));
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<List<HistoryEntry>> listRecentHistoryByUsername(String username, int limit) {
        return supplyAsync(() -> {
            int boundedLimit = Math.max(1, limit);
            String sql = """
                    SELECT ip, account_type, observed_at
                    FROM pa_ip_history
                    WHERE username = ?
                    ORDER BY observed_at DESC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, canonicalUsername(username));
                statement.setInt(2, boundedLimit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<HistoryEntry> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(new HistoryEntry(
                                resultSet.getString("ip"),
                                AccountType.valueOf(resultSet.getString("account_type")),
                                resultSet.getTimestamp("observed_at").toInstant()
                        ));
                    }
                    return rows;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<IpSummary>> topIpsByDistinctUsernamesSince(Instant since, int limit) {
        return supplyAsync(() -> {
            int boundedLimit = Math.max(1, limit);
            String sql = """
                    SELECT ip, COUNT(DISTINCT username) AS distinct_usernames, COUNT(*) AS total_hits
                    FROM pa_ip_history
                    WHERE observed_at >= ?
                    GROUP BY ip
                    ORDER BY distinct_usernames DESC, total_hits DESC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(since));
                statement.setInt(2, boundedLimit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<IpSummary> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(new IpSummary(
                                resultSet.getString("ip"),
                                resultSet.getInt("distinct_usernames"),
                                resultSet.getInt("total_hits")
                        ));
                    }
                    return rows;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<UserSummary>> topUsersByDistinctIpsSince(Instant since, int limit) {
        return supplyAsync(() -> {
            int boundedLimit = Math.max(1, limit);
            String sql = """
                    SELECT username, COUNT(DISTINCT ip) AS distinct_ips, COUNT(*) AS total_hits
                    FROM pa_ip_history
                    WHERE observed_at >= ?
                    GROUP BY username
                    ORDER BY distinct_ips DESC, total_hits DESC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(since));
                statement.setInt(2, boundedLimit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<UserSummary> rows = new ArrayList<>();
                    while (resultSet.next()) {
                        rows.add(new UserSummary(
                                resultSet.getString("username"),
                                resultSet.getInt("distinct_ips"),
                                resultSet.getInt("total_hits")
                        ));
                    }
                    return rows;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveProxyAssertion(ProxyBridgeAssertion assertion) {
        return runAsync(() -> {
            String deleteSql = """
                    DELETE FROM pa_proxy_assertions
                    WHERE username = ? AND ip = ?
                    """;
            String insertSql = """
                    INSERT INTO pa_proxy_assertions
                    (nonce, username, resolved_name, uuid, account_type, ip, join_mode, target_server, auth_entry_server, auth_entry_enforced, post_auth_server, network_authenticated, issued_at, expires_at, signature)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        resolved_name = VALUES(resolved_name),
                        uuid = VALUES(uuid),
                        account_type = VALUES(account_type),
                        ip = VALUES(ip),
                        join_mode = VALUES(join_mode),
                        target_server = VALUES(target_server),
                        auth_entry_server = VALUES(auth_entry_server),
                        auth_entry_enforced = VALUES(auth_entry_enforced),
                        post_auth_server = VALUES(post_auth_server),
                        network_authenticated = VALUES(network_authenticated),
                        issued_at = VALUES(issued_at),
                        expires_at = VALUES(expires_at),
                        signature = VALUES(signature)
                    """;
            try (Connection connection = connection()) {
                try (PreparedStatement deleteStatement = connection.prepareStatement(deleteSql)) {
                    deleteStatement.setString(1, assertion.username());
                    deleteStatement.setString(2, assertion.ipAddress());
                    deleteStatement.executeUpdate();
                }
                try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                    insertStatement.setString(1, assertion.nonce());
                    insertStatement.setString(2, assertion.username());
                    insertStatement.setString(3, assertion.resolvedName());
                    insertStatement.setString(4, assertion.uuid().toString());
                    insertStatement.setString(5, assertion.accountType().name());
                    insertStatement.setString(6, assertion.ipAddress());
                    insertStatement.setString(7, assertion.joinMode().name());
                    insertStatement.setString(8, assertion.targetServer());
                    insertStatement.setString(9, assertion.authEntryServer());
                    insertStatement.setBoolean(10, assertion.authEntryEnforced());
                    insertStatement.setString(11, assertion.postAuthServer());
                    insertStatement.setBoolean(12, assertion.networkAuthenticated());
                    insertStatement.setTimestamp(13, Timestamp.from(assertion.issuedAt()));
                    insertStatement.setTimestamp(14, Timestamp.from(assertion.expiresAt()));
                    insertStatement.setString(15, assertion.signature());
                    insertStatement.executeUpdate();
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertion(String username, String ip) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, resolved_name, uuid, account_type, ip, join_mode, target_server, auth_entry_server, auth_entry_enforced, post_auth_server, network_authenticated, issued_at, expires_at, nonce, signature
                    FROM pa_proxy_assertions
                    WHERE username = ? AND ip = ? AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY issued_at DESC
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, ip);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapProxyAssertion(resultSet));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertionByUsername(String username) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, resolved_name, uuid, account_type, ip, join_mode, target_server, auth_entry_server, auth_entry_enforced, post_auth_server, network_authenticated, issued_at, expires_at, nonce, signature
                    FROM pa_proxy_assertions
                    WHERE username = ?
                    ORDER BY issued_at DESC
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(mapProxyAssertion(resultSet));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteProxyAssertion(String nonce) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_proxy_assertions WHERE nonce = ?")) {
                statement.setString(1, nonce);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Integer> deleteExpiredProxyAssertions() {
        return supplyAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_proxy_assertions WHERE expires_at < CURRENT_TIMESTAMP")) {
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<IpBanRecord>> findActiveBan(String ip) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT ip, expires_at, reason
                    FROM pa_ip_bans
                    WHERE ip = ? AND expires_at > CURRENT_TIMESTAMP
                    LIMIT 1
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ip);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new IpBanRecord(
                            resultSet.getString("ip"),
                            resultSet.getTimestamp("expires_at").toInstant(),
                            resultSet.getString("reason")
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> listActiveBannedIps(String prefix, int limit) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT ip
                    FROM pa_ip_bans
                    WHERE expires_at > CURRENT_TIMESTAMP
                      AND ip LIKE ?
                    ORDER BY banned_at DESC, ip ASC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, (prefix == null ? "" : prefix) + "%");
                statement.setInt(2, Math.max(1, Math.min(100, limit)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<String> ips = new ArrayList<>();
                    while (resultSet.next()) {
                        ips.add(resultSet.getString("ip"));
                    }
                    return ips;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> banIp(String ip, Instant expiresAt, @Nullable String reason) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_ip_bans (ip, expires_at, reason)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        banned_at = CURRENT_TIMESTAMP,
                        expires_at = VALUES(expires_at),
                        reason = VALUES(reason)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ip);
                statement.setTimestamp(2, Timestamp.from(expiresAt));
                setNullableString(statement, 3, reason);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Void> unbanIp(String ip) {
        return runAsync(() -> {
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement("DELETE FROM pa_ip_bans WHERE ip = ?")) {
                statement.setString(1, ip);
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<StatsSnapshot> fetchStats() {
        return supplyAsync(() -> {
            try (Connection connection = connection()) {
                long registered = count(connection, "SELECT COUNT(*) FROM pa_accounts");
                long premium = count(connection, "SELECT COUNT(*) FROM pa_accounts WHERE account_type = 'PREMIUM'");
                long activeSessions = count(connection, "SELECT COUNT(*) FROM pa_sessions WHERE expires_at > CURRENT_TIMESTAMP");
                return new StatsSnapshot(registered, premium, activeSessions);
            }
        });
    }

    @Override
    public synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        ioExecutor.shutdownNow();
    }

    private synchronized void rebuildDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("ProudAuthPool");
        hikariConfig.setJdbcUrl("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&serverTimezone=UTC"
                .formatted(
                        settings.database().host(),
                        settings.database().port(),
                        settings.database().name()
                ));
        hikariConfig.setUsername(settings.database().user());
        hikariConfig.setPassword(settings.database().password());
        hikariConfig.setMaximumPoolSize(settings.database().poolSize());
        hikariConfig.setMinimumIdle(Math.min(2, settings.database().poolSize()));
        hikariConfig.setConnectionTimeout(5000L);
        hikariConfig.setValidationTimeout(3000L);
        this.dataSource = new HikariDataSource(hikariConfig);
    }

    private void migrateSchema() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_accounts (
                        uuid VARCHAR(36) NOT NULL PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        password_hash VARCHAR(72),
                        account_type ENUM('PREMIUM','CRACKED') NOT NULL DEFAULT 'CRACKED',
                        email VARCHAR(255),
                        totp_secret VARCHAR(255),
                        totp_flow_always TINYINT(1) NOT NULL DEFAULT 0,
                        registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_login_at DATETIME,
                        last_ip VARCHAR(45)
                    )
                    """);
            statement.executeUpdate("ALTER TABLE pa_accounts ADD COLUMN IF NOT EXISTS totp_flow_always TINYINT(1) NOT NULL DEFAULT 0");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_sessions (
                        token VARCHAR(64) NOT NULL PRIMARY KEY,
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        account_type ENUM('PREMIUM','CRACKED') NOT NULL DEFAULT 'CRACKED',
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at DATETIME NOT NULL,
                        FOREIGN KEY (uuid) REFERENCES pa_accounts(uuid) ON DELETE CASCADE,
                        INDEX idx_session_uuid (uuid),
                        INDEX idx_session_expires (expires_at)
                    )
                    """);
            statement.executeUpdate("ALTER TABLE pa_sessions ADD COLUMN IF NOT EXISTS account_type ENUM('PREMIUM','CRACKED') NOT NULL DEFAULT 'CRACKED'");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_ip_bans (
                        ip VARCHAR(45) NOT NULL PRIMARY KEY,
                        banned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at DATETIME NOT NULL,
                        reason VARCHAR(255)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_proxy_assertions (
                        nonce VARCHAR(64) NOT NULL PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        resolved_name VARCHAR(16) NOT NULL,
                        uuid VARCHAR(36) NOT NULL,
                        account_type ENUM('PREMIUM','CRACKED') NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        join_mode VARCHAR(32) NOT NULL DEFAULT 'NETWORK_TRANSFER',
                        target_server VARCHAR(64) NOT NULL DEFAULT '',
                        auth_entry_server VARCHAR(64) NOT NULL DEFAULT '',
                        auth_entry_enforced TINYINT(1) NOT NULL DEFAULT 0,
                        post_auth_server VARCHAR(64) NOT NULL DEFAULT '',
                        network_authenticated TINYINT(1) NOT NULL DEFAULT 0,
                        issued_at DATETIME NOT NULL,
                        expires_at DATETIME NOT NULL,
                        signature VARCHAR(128) NOT NULL,
                        INDEX idx_proxy_assertion_lookup (username, ip, expires_at),
                        INDEX idx_proxy_assertion_expires (expires_at)
                    )
                    """);
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS join_mode VARCHAR(32) NOT NULL DEFAULT 'NETWORK_TRANSFER'");
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS target_server VARCHAR(64) NOT NULL DEFAULT ''");
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS auth_entry_server VARCHAR(64) NOT NULL DEFAULT ''");
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS auth_entry_enforced TINYINT(1) NOT NULL DEFAULT 0");
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS post_auth_server VARCHAR(64) NOT NULL DEFAULT ''");
            statement.executeUpdate("ALTER TABLE pa_proxy_assertions ADD COLUMN IF NOT EXISTS network_authenticated TINYINT(1) NOT NULL DEFAULT 0");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_backend_join_probes (
                        probe_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        target_server VARCHAR(64) NOT NULL,
                        issued_at DATETIME NOT NULL,
                        expires_at DATETIME NOT NULL,
                        acknowledged_at DATETIME,
                        responder_id VARCHAR(64),
                        INDEX idx_backend_probe_expires (expires_at),
                        INDEX idx_backend_probe_pending (target_server, acknowledged_at, expires_at)
                    )
                    """);
            statement.executeUpdate("ALTER TABLE pa_backend_join_probes ADD COLUMN IF NOT EXISTS target_server VARCHAR(64) NOT NULL DEFAULT ''");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_ip_history (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        account_type ENUM('PREMIUM','CRACKED') NOT NULL,
                        observed_at DATETIME NOT NULL,
                        INDEX idx_ip_history_ip_time (ip, observed_at),
                        INDEX idx_ip_history_user_time (username, observed_at)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_identity_claims (
                        username VARCHAR(16) NOT NULL PRIMARY KEY,
                        claim_type ENUM('PREMIUM','CRACKED') NOT NULL,
                        claimed_ip VARCHAR(45),
                        claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        INDEX idx_identity_claim_type (claim_type)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_pending_identity_claims (
                        username VARCHAR(16) NOT NULL PRIMARY KEY,
                        claim_type ENUM('PREMIUM','CRACKED') NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at DATETIME NOT NULL,
                        INDEX idx_pending_claim_expires (expires_at)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_trusted_ips (
                        username VARCHAR(16) NOT NULL PRIMARY KEY,
                        ip VARCHAR(45) NOT NULL,
                        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_premium_ip_whitelist (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_whitelist_user_ip (username, ip),
                        INDEX idx_whitelist_username (username)
                    )
                    """);
            ensureIndex(connection, "pa_accounts", "idx_accounts_username", "CREATE INDEX idx_accounts_username ON pa_accounts (username)");
            ensureIndex(connection, "pa_accounts", "idx_accounts_last_login", "CREATE INDEX idx_accounts_last_login ON pa_accounts (last_login_at)");
            ensureIndex(connection, "pa_ip_bans", "idx_ip_bans_active", "CREATE INDEX idx_ip_bans_active ON pa_ip_bans (expires_at, banned_at, ip)");
            statement.executeUpdate("DELETE FROM pa_sessions WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_ip_bans WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_proxy_assertions WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_backend_join_probes WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_pending_identity_claims WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_ip_history WHERE observed_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 30 DAY)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossibile inizializzare lo schema MySQL.", exception);
        }
    }

    private void ensureIndex(Connection connection, String tableName, String indexName, String createIndexSql) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet resultSet = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (resultSet.next()) {
                if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createIndexSql);
        }
    }

    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    private AccountRecord mapAccount(ResultSet resultSet) throws SQLException {
        return new AccountRecord(
                UUID.fromString(resultSet.getString("uuid")),
                resultSet.getString("username"),
                resultSet.getString("password_hash"),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getString("email"),
                resultSet.getString("totp_secret"),
                resultSet.getBoolean("totp_flow_always"),
                resultSet.getTimestamp("registered_at").toInstant(),
                nullableTimestamp(resultSet, "last_login_at"),
                resultSet.getString("last_ip")
        );
    }

    private Session mapSession(ResultSet resultSet) throws SQLException {
        return new Session(
                resultSet.getString("token"),
                resultSet.getString("ip"),
                UUID.fromString(resultSet.getString("uuid")),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getTimestamp("expires_at").toInstant()
        );
    }

    private ProxyBridgeAssertion mapProxyAssertion(ResultSet resultSet) throws SQLException {
        return new ProxyBridgeAssertion(
                resultSet.getString("username"),
                resultSet.getString("resolved_name"),
                UUID.fromString(resultSet.getString("uuid")),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getString("ip"),
                BridgeJoinMode.from(resultSet.getString("join_mode")),
                resultSet.getString("target_server"),
                resultSet.getString("auth_entry_server"),
                resultSet.getBoolean("auth_entry_enforced"),
                resultSet.getString("post_auth_server"),
                resultSet.getBoolean("network_authenticated"),
                resultSet.getTimestamp("issued_at").toInstant(),
                resultSet.getTimestamp("expires_at").toInstant(),
                resultSet.getString("nonce"),
                resultSet.getString("signature")
        );
    }

    private long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static String canonicalUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private <T> CompletableFuture<T> supplyAsync(SqlSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor);
    }

    private CompletableFuture<Void> runAsync(SqlRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (SQLException exception) {
                throw new CompletionException(exception);
            }
        }, ioExecutor);
    }

    private static void setNullableString(PreparedStatement statement, int index, @Nullable String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.VARCHAR);
            return;
        }
        statement.setString(index, value);
    }

    private static void setNullableTimestamp(PreparedStatement statement, int index, @Nullable Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP);
            return;
        }
        statement.setTimestamp(index, Timestamp.from(value));
    }

    private static @Nullable Instant nullableTimestamp(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    @FunctionalInterface
    private interface SqlRunnable {
        void run() throws SQLException;
    }

    private static final class StorageThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ProudAuth-Storage-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
