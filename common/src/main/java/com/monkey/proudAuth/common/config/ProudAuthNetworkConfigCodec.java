package com.monkey.proudAuth.common.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.*;
import java.util.stream.Collectors;

public final class ProudAuthNetworkConfigCodec {

    private final Yaml loadYaml;
    private final Yaml dumpYaml;

    public ProudAuthNetworkConfigCodec() {
        LoaderOptions loaderOptions = new LoaderOptions();
        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setPrettyFlow(true);
        this.loadYaml = new Yaml(new SafeConstructor(loaderOptions));
        this.dumpYaml = new Yaml(dumperOptions);
    }

    public ProudAuthNetworkConfig parse(String document) {
        Object loaded = loadYaml.load(document);
        Map<String, Object> root = loaded instanceof Map<?, ?> map
                ? cast(map)
                : Collections.emptyMap();
        return fromMap(root);
    }

    public String dump(ProudAuthNetworkConfig config) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("language", config.language());
        root.put("database", dumpDatabase(config.database()));
        root.put("debugger", dumpDebugger(config.debugger()));
        root.put("premium", dumpPremium(config));
        root.put("sessions", dumpSessions(config.sessions()));
        root.put("security", dumpSecurity(config.security()));
        root.put("protection", dumpProtection(config.protection()));
        root.put("password", dumpPassword(config.passwordPolicy()));
        root.put("bridge", dumpBridge(config.bridge(), config.proxy().bridgeBackendCheck()));
        root.put("routing", dumpRouting(config.proxy().routing()));
        root.put("guards", dumpGuards(config.proxy().guards()));
        root.put("reports", dumpReports(config.proxy().reports()));
        return dumpYaml.dump(root);
    }

    public ProudAuthNetworkConfig fromMap(Map<String, Object> root) {
        ProudAuthSettings.Database database = new ProudAuthSettings.Database(
                string(root, "database.host", "localhost"),
                integer(root, "database.port", 3306),
                string(root, "database.name", "proudauth"),
                string(root, "database.user", "root"),
                string(root, "database.password", ""),
                Math.max(1, integer(root, "database.pool-size", 10))
        );

        ProudAuthSettings.Premium premium = new ProudAuthSettings.Premium(
                bool(root, "premium.enabled", true),
                bool(root, "premium.auto-login", true),
                Math.max(500, integer(root, "premium.api-timeout-ms", 3000)),
                bool(root, "premium.require-uuid-proof", true),
                ProudAuthSettings.PremiumMode.from(string(root, "premium.mode", "STRICT_GLOBAL")),
                Math.max(30L, longValue(root, "premium.claim-pending-seconds", 300L)),
                ProudAuthSettings.LegacyUnsupportedAction.from(string(
                        root,
                        "premium.proxy-custom.legacy-unsupported-action",
                        "FORCE_ONLINE"
                ))
        );

        ProudAuthSettings.Sessions sessions = new ProudAuthSettings.Sessions(
                bool(root, "sessions.enabled", true),
                Math.max(0L, longValue(root, "sessions.ttl-minutes", 1440L)),
                bool(root, "sessions.bind-to-ip", true)
        );

        ProudAuthSettings.Security security = new ProudAuthSettings.Security(
                Math.max(1, integer(root, "security.max-attempts", 5)),
                Math.max(1L, longValue(root, "security.lockout-seconds", 300L)),
                bool(root, "security.totp-enabled", false),
                Math.max(30, integer(root, "security.totp-setup-timeout-seconds", 180)),
                Math.max(1, integer(root, "security.totp-setup-max-attempts", 3)),
                bool(root, "security.totp-default-flow-always", false),
                Math.max(1, integer(root, "security.totp-suspicious.max-usernames-per-ip-1h", 2)),
                Math.max(1, integer(root, "security.totp-suspicious.max-ips-per-username-24h", 3)),
                bool(root, "security.totp-suspicious.deny-when-ip-banned", true)
        );

        ProudAuthSettings.Protection protection = new ProudAuthSettings.Protection(
                bool(root, "protection.block-movement", true),
                bool(root, "protection.block-chat", true),
                bool(root, "protection.block-interactions", true),
                bool(root, "protection.invisible-until-auth", true),
                bool(root, "protection.no-drop-on-death", true),
                Math.max(5L, longValue(root, "protection.auth-timeout-seconds", 60L)),
                new ProudAuthSettings.AuthSpawn(
                        bool(root, "protection.auth-spawn.enabled", false),
                        bool(root, "protection.auth-spawn.use-world-spawn", false),
                        string(root, "protection.auth-spawn.world", "world"),
                        doubleValue(root, "protection.auth-spawn.x", 0.5D),
                        doubleValue(root, "protection.auth-spawn.y", 64D),
                        doubleValue(root, "protection.auth-spawn.z", 0.5D),
                        (float) doubleValue(root, "protection.auth-spawn.yaw", 0D),
                        (float) doubleValue(root, "protection.auth-spawn.pitch", 0D)
                ),
                bool(root, "protection.commands.block-commands", true),
                normalizedCommands(stringList(root, "protection.commands.allowed-while-protected"),
                        List.of("/login", "/register", "/2fa", "/sp", "/cracked", "/premium", "/proudauthbackend")),
                normalizedCommands(stringList(root, "protection.commands.allowed-during-claim-choice"),
                        List.of("/sp", "/cracked", "/premium", "/proudauthbackend")),
                bool(root, "protection.restrictions.block-block-break", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-block-place", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-pvp-attack", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-pvp-take-damage", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-item-pickup", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-food-level-change", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-item-consume", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-swap-hand-items", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-book-edit", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-inventory", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-item-drop", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-entity-interact", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.restrictions.block-world-interact", bool(root, "protection.block-interactions", true)),
                bool(root, "protection.visibility.hide-protected-player-from-others", bool(root, "protection.invisible-until-auth", true)),
                bool(root, "protection.visibility.hide-other-players-from-protected-player", bool(root, "protection.invisible-until-auth", true)),
                ProudAuthSettings.VisualEffect.from(string(root, "protection.effects.visual-effect", "NONE"))
        );

        ProudAuthSettings.PasswordPolicy passwordPolicy = new ProudAuthSettings.PasswordPolicy(
                Math.max(1, integer(root, "password.min-length", 6)),
                Math.max(1, integer(root, "password.max-length", 32)),
                string(root, "password.complexity-regex", "")
        );

        ProudAuthSettings.Bridge bridge = new ProudAuthSettings.Bridge(
                bool(root, "bridge.enabled", false),
                ProudAuthSettings.BridgeMode.from(string(root, "bridge.mode", "FALLBACK")),
                ProudAuthSettings.BridgeTransport.from(string(root, "bridge.transport", "MYSQL")),
                string(root, "bridge.shared-secret", "change-me"),
                Math.max(1L, longValue(root, "bridge.assertion-ttl-seconds", 10L))
        );

        ProudAuthSettings.Debugger debugger = new ProudAuthSettings.Debugger(
                bool(root, "debugger.enabled", false),
                bool(root, "debugger.player-resolution", true),
                bool(root, "debugger.session-flow", true),
                bool(root, "debugger.premium-flow", true),
                bool(root, "debugger.bridge-flow", true),
                bool(root, "debugger.security-flow", false),
                bool(root, "debugger.protection-flow", true),
                bool(root, "debugger.ip-ban-flow", true),
                bool(root, "debugger.profile-flow", true),
                bool(root, "debugger.movement-audit", false),
                bool(root, "debugger.teleport-audit", false),
                bool(root, "debugger.command-flow", true)
        );

        ProudAuthNetworkConfig.Proxy proxy = new ProudAuthNetworkConfig.Proxy(
                string(root, "premium.online-mode-denied-message",
                        "<dark_gray>[</dark_gray><aqua>ProudMC</aqua><dark_gray>] </dark_gray><red>Accesso rifiutato</red><newline><gray>Questo nickname premium richiede un account Minecraft premium autenticato.</gray>"),
                string(root, "premium.claim-failed-denied-message",
                        "<dark_gray>[</dark_gray><aqua>ProudMC</aqua><dark_gray>] </dark_gray><red>Verifica premium fallita</red><newline><gray>Non e stato possibile confermare questo nickname come premium.</gray><newline><yellow>Rientra e scegli di nuovo <white>/sp</white><yellow> oppure <white>/premium</white><yellow>.</yellow>"),
                new ProudAuthNetworkConfig.BridgeBackendCheck(
                        bool(root, "bridge.backend-check.enabled", true),
                        Math.max(250, integer(root, "bridge.backend-check.timeout-ms", 2500)),
                        Math.max(25, integer(root, "bridge.backend-check.poll-interval-ms", 100))
                ),
                new ProudAuthNetworkConfig.Routing(
                        bool(root, "routing.auth-entry.enabled", false),
                        string(root, "routing.auth-entry.server", ""),
                        bool(root, "routing.post-auth.enabled", false),
                        string(root, "routing.post-auth.server", "")
                ),
                new ProudAuthNetworkConfig.Guards(
                        bool(root, "guards.enabled", true),
                        Math.max(1, integer(root, "guards.antibot.window-seconds", 12)),
                        Math.max(1, integer(root, "guards.antibot.max-connections-per-ip", 8)),
                        Math.max(1L, longValue(root, "guards.antibot.ban-seconds", 900L)),
                        Math.max(5, integer(root, "guards.identity.window-seconds", 3600)),
                        Math.max(1, integer(root, "guards.identity.max-usernames-per-ip", 4)),
                        Math.max(1, integer(root, "guards.identity.max-ips-per-username", 5)),
                        Math.max(1L, longValue(root, "guards.identity.ban-seconds", 7200L)),
                        Math.max(1, integer(root, "guards.history-retention-days", 30))
                ),
                new ProudAuthNetworkConfig.Reports(
                        bool(root, "reports.auto-export.enabled", true),
                        Math.max(1, integer(root, "reports.auto-export.interval-minutes", 60)),
                        Math.max(1, integer(root, "reports.auto-export.window-hours", 24)),
                        Math.max(1, integer(root, "reports.auto-export.limit", 50))
                )
        );

        return new ProudAuthNetworkConfig(
                string(root, "language", "it"),
                database,
                premium,
                sessions,
                security,
                protection,
                passwordPolicy,
                bridge,
                debugger,
                proxy
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> cast(Map<?, ?> rawMap) {
        return (Map<String, Object>) rawMap;
    }

    @SuppressWarnings("unchecked")
    private Object get(Map<String, Object> root, String path) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String string(Map<String, Object> root, String path, String fallback) {
        Object value = get(root, path);
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> root, String path, boolean fallback) {
        Object value = get(root, path);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private int integer(Map<String, Object> root, String path, int fallback) {
        Object value = get(root, path);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private long longValue(Map<String, Object> root, String path, long fallback) {
        Object value = get(root, path);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private double doubleValue(Map<String, Object> root, String path, double fallback) {
        Object value = get(root, path);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Map<String, Object> root, String path) {
        Object value = get(root, path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .collect(Collectors.toUnmodifiableList());
    }

    private List<String> normalizedCommands(List<String> configuredValues, List<String> fallbackValues) {
        List<String> source = configuredValues == null || configuredValues.isEmpty() ? fallbackValues : configuredValues;
        return source.stream()
                .map(this::normalizeCommand)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    private String normalizeCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }

    private Map<String, Object> dumpDatabase(ProudAuthSettings.Database database) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("host", database.host());
        values.put("port", database.port());
        values.put("name", database.name());
        values.put("user", database.user());
        values.put("password", database.password());
        values.put("pool-size", database.poolSize());
        return values;
    }

    private Map<String, Object> dumpDebugger(ProudAuthSettings.Debugger debugger) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", debugger.enabled());
        values.put("player-resolution", debugger.playerResolution());
        values.put("session-flow", debugger.sessionFlow());
        values.put("premium-flow", debugger.premiumFlow());
        values.put("bridge-flow", debugger.bridgeFlow());
        values.put("security-flow", debugger.securityFlow());
        values.put("protection-flow", debugger.protectionFlow());
        values.put("ip-ban-flow", debugger.ipBanFlow());
        values.put("profile-flow", debugger.profileFlow());
        values.put("movement-audit", debugger.movementAudit());
        values.put("teleport-audit", debugger.teleportAudit());
        values.put("command-flow", debugger.commandFlow());
        return values;
    }

    private Map<String, Object> dumpPremium(ProudAuthNetworkConfig config) {
        Map<String, Object> values = new LinkedHashMap<>();
        ProudAuthSettings.Premium premium = config.premium();
        values.put("enabled", premium.enabled());
        values.put("auto-login", premium.autoLogin());
        values.put("api-timeout-ms", premium.apiTimeoutMs());
        values.put("require-uuid-proof", premium.requireUuidProof());
        values.put("mode", premium.mode().name());
        values.put("claim-pending-seconds", premium.claimPendingSeconds());
        Map<String, Object> proxyCustom = new LinkedHashMap<>();
        proxyCustom.put("legacy-unsupported-action", premium.legacyUnsupportedAction().name());
        values.put("proxy-custom", proxyCustom);
        values.put("online-mode-denied-message", config.proxy().onlineModeDeniedMessage());
        values.put("claim-failed-denied-message", config.proxy().claimFailedDeniedMessage());
        return values;
    }

    private Map<String, Object> dumpSessions(ProudAuthSettings.Sessions sessions) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", sessions.enabled());
        values.put("ttl-minutes", sessions.ttlMinutes());
        values.put("bind-to-ip", sessions.bindToIp());
        return values;
    }

    private Map<String, Object> dumpSecurity(ProudAuthSettings.Security security) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("max-attempts", security.maxAttempts());
        values.put("lockout-seconds", security.lockoutSeconds());
        values.put("totp-enabled", security.totpEnabled());
        values.put("totp-setup-timeout-seconds", security.totpSetupTimeoutSeconds());
        values.put("totp-setup-max-attempts", security.totpSetupMaxAttempts());
        values.put("totp-default-flow-always", security.totpDefaultFlowAlways());
        Map<String, Object> suspicious = new LinkedHashMap<>();
        suspicious.put("max-usernames-per-ip-1h", security.totpSuspiciousMaxUsernamesPerIp1h());
        suspicious.put("max-ips-per-username-24h", security.totpSuspiciousMaxIpsPerUsername24h());
        suspicious.put("deny-when-ip-banned", security.totpSuspiciousDenyWhenIpBanned());
        values.put("totp-suspicious", suspicious);
        return values;
    }

    private Map<String, Object> dumpProtection(ProudAuthSettings.Protection protection) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("block-movement", protection.blockMovement());
        values.put("block-chat", protection.blockChat());
        values.put("block-interactions", protection.blockInteractions());
        values.put("invisible-until-auth", protection.invisibleUntilAuth());
        values.put("no-drop-on-death", protection.noDropOnDeath());
        values.put("auth-timeout-seconds", protection.authTimeoutSeconds());

        Map<String, Object> commands = new LinkedHashMap<>();
        commands.put("block-commands", protection.blockCommands());
        commands.put("allowed-while-protected", protection.allowedCommandsWhileProtected());
        commands.put("allowed-during-claim-choice", protection.allowedCommandsDuringClaimChoice());
        values.put("commands", commands);

        Map<String, Object> restrictions = new LinkedHashMap<>();
        restrictions.put("block-block-break", protection.blockBlockBreak());
        restrictions.put("block-block-place", protection.blockBlockPlace());
        restrictions.put("block-pvp-attack", protection.blockPvPAttack());
        restrictions.put("block-pvp-take-damage", protection.blockPvPTakeDamage());
        restrictions.put("block-item-pickup", protection.blockItemPickup());
        restrictions.put("block-food-level-change", protection.blockFoodLevelChange());
        restrictions.put("block-item-consume", protection.blockItemConsume());
        restrictions.put("block-swap-hand-items", protection.blockSwapHandItems());
        restrictions.put("block-book-edit", protection.blockBookEdit());
        restrictions.put("block-inventory", protection.blockInventory());
        restrictions.put("block-item-drop", protection.blockItemDrop());
        restrictions.put("block-entity-interact", protection.blockEntityInteract());
        restrictions.put("block-world-interact", protection.blockWorldInteract());
        values.put("restrictions", restrictions);

        Map<String, Object> visibility = new LinkedHashMap<>();
        visibility.put("hide-protected-player-from-others", protection.hideProtectedPlayerFromOthers());
        visibility.put("hide-other-players-from-protected-player", protection.hideOtherPlayersFromProtectedPlayer());
        values.put("visibility", visibility);

        Map<String, Object> effects = new LinkedHashMap<>();
        effects.put("visual-effect", protection.visualEffect().name());
        values.put("effects", effects);

        ProudAuthSettings.AuthSpawn authSpawn = protection.authSpawn();
        Map<String, Object> authSpawnValues = new LinkedHashMap<>();
        authSpawnValues.put("use-world-spawn", authSpawn.useWorldSpawn());
        authSpawnValues.put("enabled", authSpawn.enabled());
        authSpawnValues.put("world", authSpawn.world());
        authSpawnValues.put("x", authSpawn.x());
        authSpawnValues.put("y", authSpawn.y());
        authSpawnValues.put("z", authSpawn.z());
        authSpawnValues.put("yaw", authSpawn.yaw());
        authSpawnValues.put("pitch", authSpawn.pitch());
        values.put("auth-spawn", authSpawnValues);

        return values;
    }

    private Map<String, Object> dumpPassword(ProudAuthSettings.PasswordPolicy passwordPolicy) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("min-length", passwordPolicy.minLength());
        values.put("max-length", passwordPolicy.maxLength());
        values.put("complexity-regex", passwordPolicy.complexityRegex());
        return values;
    }

    private Map<String, Object> dumpBridge(ProudAuthSettings.Bridge bridge) {
        return dumpBridge(bridge, new ProudAuthNetworkConfig.BridgeBackendCheck(true, 2500, 100));
    }

    private Map<String, Object> dumpBridge(ProudAuthSettings.Bridge bridge, ProudAuthNetworkConfig.BridgeBackendCheck backendCheckSettings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", bridge.enabled());
        values.put("mode", bridge.mode().name());
        values.put("transport", bridge.transport().name());
        values.put("shared-secret", bridge.sharedSecret());
        values.put("assertion-ttl-seconds", bridge.assertionTtlSeconds());
        Map<String, Object> backendCheck = new LinkedHashMap<>();
        backendCheck.put("enabled", backendCheckSettings.enabled());
        backendCheck.put("timeout-ms", backendCheckSettings.timeoutMs());
        backendCheck.put("poll-interval-ms", backendCheckSettings.pollIntervalMs());
        values.put("backend-check", backendCheck);
        return values;
    }

    private Map<String, Object> dumpRouting(ProudAuthNetworkConfig.Routing routing) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> authEntry = new LinkedHashMap<>();
        authEntry.put("enabled", routing.authEntryEnabled());
        authEntry.put("server", routing.authEntryServer());
        values.put("auth-entry", authEntry);
        Map<String, Object> postAuth = new LinkedHashMap<>();
        postAuth.put("enabled", routing.postAuthEnabled());
        postAuth.put("server", routing.postAuthServer());
        values.put("post-auth", postAuth);
        return values;
    }

    private Map<String, Object> dumpGuards(ProudAuthNetworkConfig.Guards guards) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", guards.enabled());
        Map<String, Object> antibot = new LinkedHashMap<>();
        antibot.put("window-seconds", guards.antiBotWindowSeconds());
        antibot.put("max-connections-per-ip", guards.antiBotMaxConnectionsPerIp());
        antibot.put("ban-seconds", guards.antiBotBanSeconds());
        values.put("antibot", antibot);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("window-seconds", guards.identityWindowSeconds());
        identity.put("max-usernames-per-ip", guards.identityMaxUsernamesPerIp());
        identity.put("max-ips-per-username", guards.identityMaxIpsPerUsername());
        identity.put("ban-seconds", guards.identityBanSeconds());
        values.put("identity", identity);
        values.put("history-retention-days", guards.historyRetentionDays());
        return values;
    }

    private Map<String, Object> dumpReports(ProudAuthNetworkConfig.Reports reports) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> autoExport = new LinkedHashMap<>();
        autoExport.put("enabled", reports.autoExportEnabled());
        autoExport.put("interval-minutes", reports.autoExportIntervalMinutes());
        autoExport.put("window-hours", reports.autoExportWindowHours());
        autoExport.put("limit", reports.autoExportLimit());
        values.put("auto-export", autoExport);
        return values;
    }
}
