package com.monkey.proudAuth.common.storage.impl;

import com.monkey.proudAuth.common.bridge.ProxyBridgeAssertion;
import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.model.AccountType;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.storage.AccountRecord;
import com.monkey.proudAuth.common.storage.IpBanRecord;
import com.monkey.proudAuth.common.storage.StatsSnapshot;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
                    SELECT uuid, username, password_hash, account_type, email, totp_secret, registered_at, last_login_at, last_ip
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
                    SELECT uuid, username, password_hash, account_type, email, totp_secret, registered_at, last_login_at, last_ip
                    FROM pa_accounts
                    WHERE username = ?
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
    public CompletableFuture<Void> saveAccount(AccountRecord accountRecord) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_accounts
                    (uuid, username, password_hash, account_type, email, totp_secret, registered_at, last_login_at, last_ip)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        password_hash = IFNULL(VALUES(password_hash), password_hash),
                        account_type = VALUES(account_type),
                        email = IFNULL(VALUES(email), email),
                        totp_secret = IFNULL(VALUES(totp_secret), totp_secret),
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
                statement.setTimestamp(7, Timestamp.from(accountRecord.registeredAt()));
                setNullableTimestamp(statement, 8, accountRecord.lastLoginAt());
                setNullableString(statement, 9, accountRecord.lastIp());
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
            String sql = """
                    INSERT INTO pa_accounts (uuid, username, password_hash, account_type, registered_at, last_login_at, last_ip)
                    VALUES (?, ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        account_type = VALUES(account_type),
                        last_login_at = CURRENT_TIMESTAMP,
                        last_ip = VALUES(last_ip)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, username);
                statement.setString(3, accountType.name());
                statement.setString(4, ip);
                statement.executeUpdate();
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
    public CompletableFuture<Optional<Session>> findValidSession(UUID uuid, @Nullable String ip) {
        return supplyAsync(() -> {
            String sql = ip == null
                    ? "SELECT token, uuid, ip, expires_at FROM pa_sessions WHERE uuid = ? AND expires_at > CURRENT_TIMESTAMP LIMIT 1"
                    : "SELECT token, uuid, ip, expires_at FROM pa_sessions WHERE uuid = ? AND ip = ? AND expires_at > CURRENT_TIMESTAMP LIMIT 1";
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid.toString());
                if (ip != null) {
                    statement.setString(2, ip);
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new Session(
                            resultSet.getString("token"),
                            resultSet.getString("ip"),
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getTimestamp("expires_at").toInstant()
                    ));
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveSession(Session session) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_sessions (token, uuid, ip, expires_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        uuid = VALUES(uuid),
                        ip = VALUES(ip),
                        expires_at = VALUES(expires_at)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, session.token());
                statement.setString(2, session.uuid().toString());
                statement.setString(3, session.ip());
                statement.setTimestamp(4, Timestamp.from(session.expiresAt()));
                statement.executeUpdate();
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
            Instant issuedAt,
            Instant expiresAt
    ) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_backend_join_probes
                    (probe_id, username, ip, issued_at, expires_at, acknowledged_at, responder_id)
                    VALUES (?, ?, ?, ?, ?, NULL, NULL)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        ip = VALUES(ip),
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
                statement.setTimestamp(4, Timestamp.from(issuedAt));
                statement.setTimestamp(5, Timestamp.from(expiresAt));
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
    public CompletableFuture<Integer> acknowledgePendingBackendJoinProbes(String responderId, int maxBatchSize) {
        return supplyAsync(() -> {
            int boundedBatchSize = Math.max(1, maxBatchSize);
            String sql = """
                    UPDATE pa_backend_join_probes
                    SET acknowledged_at = CURRENT_TIMESTAMP,
                        responder_id = ?
                    WHERE acknowledged_at IS NULL
                      AND expires_at > CURRENT_TIMESTAMP
                    ORDER BY issued_at ASC
                    LIMIT ?
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, responderId);
                statement.setInt(2, boundedBatchSize);
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
    public CompletableFuture<Void> saveProxyAssertion(ProxyBridgeAssertion assertion) {
        return runAsync(() -> {
            String sql = """
                    INSERT INTO pa_proxy_assertions
                    (nonce, username, resolved_name, uuid, account_type, ip, issued_at, expires_at, signature)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        resolved_name = VALUES(resolved_name),
                        uuid = VALUES(uuid),
                        account_type = VALUES(account_type),
                        ip = VALUES(ip),
                        issued_at = VALUES(issued_at),
                        expires_at = VALUES(expires_at),
                        signature = VALUES(signature)
                    """;
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, assertion.nonce());
                statement.setString(2, assertion.username());
                statement.setString(3, assertion.resolvedName());
                statement.setString(4, assertion.uuid().toString());
                statement.setString(5, assertion.accountType().name());
                statement.setString(6, assertion.ipAddress());
                statement.setTimestamp(7, Timestamp.from(assertion.issuedAt()));
                statement.setTimestamp(8, Timestamp.from(assertion.expiresAt()));
                statement.setString(9, assertion.signature());
                statement.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<ProxyBridgeAssertion>> findLatestProxyAssertion(String username, String ip) {
        return supplyAsync(() -> {
            String sql = """
                    SELECT username, resolved_name, uuid, account_type, ip, issued_at, expires_at, nonce, signature
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
                        registered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_login_at DATETIME,
                        last_ip VARCHAR(45)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_sessions (
                        token VARCHAR(64) NOT NULL PRIMARY KEY,
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        expires_at DATETIME NOT NULL,
                        FOREIGN KEY (uuid) REFERENCES pa_accounts(uuid) ON DELETE CASCADE,
                        INDEX idx_session_uuid (uuid),
                        INDEX idx_session_expires (expires_at)
                    )
                    """);
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
                        issued_at DATETIME NOT NULL,
                        expires_at DATETIME NOT NULL,
                        signature VARCHAR(128) NOT NULL,
                        INDEX idx_proxy_assertion_lookup (username, ip, expires_at),
                        INDEX idx_proxy_assertion_expires (expires_at)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pa_backend_join_probes (
                        probe_id VARCHAR(64) NOT NULL PRIMARY KEY,
                        username VARCHAR(16) NOT NULL,
                        ip VARCHAR(45) NOT NULL,
                        issued_at DATETIME NOT NULL,
                        expires_at DATETIME NOT NULL,
                        acknowledged_at DATETIME,
                        responder_id VARCHAR(64),
                        INDEX idx_backend_probe_expires (expires_at),
                        INDEX idx_backend_probe_pending (acknowledged_at, expires_at)
                    )
                    """);
            statement.executeUpdate("DELETE FROM pa_sessions WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_ip_bans WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_proxy_assertions WHERE expires_at < CURRENT_TIMESTAMP");
            statement.executeUpdate("DELETE FROM pa_backend_join_probes WHERE expires_at < CURRENT_TIMESTAMP");
        } catch (SQLException exception) {
            throw new IllegalStateException("Impossibile inizializzare lo schema MySQL.", exception);
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
                resultSet.getTimestamp("registered_at").toInstant(),
                nullableTimestamp(resultSet, "last_login_at"),
                resultSet.getString("last_ip")
        );
    }

    private ProxyBridgeAssertion mapProxyAssertion(ResultSet resultSet) throws SQLException {
        return new ProxyBridgeAssertion(
                resultSet.getString("username"),
                resultSet.getString("resolved_name"),
                UUID.fromString(resultSet.getString("uuid")),
                AccountType.valueOf(resultSet.getString("account_type")),
                resultSet.getString("ip"),
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
