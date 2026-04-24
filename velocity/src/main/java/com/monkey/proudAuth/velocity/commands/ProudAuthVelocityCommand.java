package com.monkey.proudAuth.velocity.commands;

import com.monkey.proudAuth.common.storage.IpHistoryStorage;
import com.monkey.proudAuth.common.storage.StorageProvider;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.security.VelocityRiskCsvExporter;
import com.monkey.proudAuth.velocity.security.VelocitySecurityInspectorService;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class ProudAuthVelocityCommand implements SimpleCommand {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<VelocitySecurityInspectorService> securityInspectorSupplier;
    private final Supplier<VelocityRiskCsvExporter> csvExporterSupplier;
    private final Supplier<StorageProvider> storageSupplier;
    private final Runnable reloadAction;

    public ProudAuthVelocityCommand(
            Supplier<VelocityLang> langSupplier,
            Supplier<VelocitySecurityInspectorService> securityInspectorSupplier,
            Supplier<VelocityRiskCsvExporter> csvExporterSupplier,
            Supplier<StorageProvider> storageSupplier,
            Runnable reloadAction
    ) {
        this.langSupplier = langSupplier;
        this.securityInspectorSupplier = securityInspectorSupplier;
        this.csvExporterSupplier = csvExporterSupplier;
        this.storageSupplier = storageSupplier;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(Invocation invocation) {
        VelocityLang lang = langSupplier.get();
        String[] args = invocation.arguments();
        if (args.length == 0) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy <reload|iphistory|risk|risk-top|banwave-ip|export-risk-csv>"));
            return;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "reload" -> handleReload(invocation, lang);
            case "iphistory" -> handleIpHistory(invocation, args, lang);
            case "risk" -> handleRisk(invocation, args, lang);
            case "risk-top" -> handleRiskTop(invocation, args, lang);
            case "banwave-ip" -> handleBanwaveIp(invocation, args, lang);
            case "export-risk-csv" -> handleExportRiskCsv(invocation, args, lang);
            default -> lang.send(invocation.source(), "admin-unknown-subcommand");
        }
    }

    private void handleReload(Invocation invocation, VelocityLang lang) {
        if (!hasReloadPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }
        reloadAction.run();
        lang.send(invocation.source(), "admin-reload");
    }

    private void handleIpHistory(Invocation invocation, String[] args, VelocityLang lang) {
        if (!hasSecurityPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }
        if (args.length < 2 || args[1].isBlank()) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy iphistory <ip>"));
            return;
        }

        String ipAddress = args[1];
        securityInspectorSupplier.get()
                .inspectIp(ipAddress)
                .whenComplete((report, exception) -> {
                    if (exception != null) {
                        lang.send(invocation.source(), "error-generic");
                        return;
                    }
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>[ProudAuth]</gray> <aqua>IP History</aqua> <white>" + report.ipAddress() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- usernames 1h:</gray> <white>" + report.distinctUsernames1h() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- usernames 24h:</gray> <white>" + report.distinctUsernames24h() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- usernames 7d:</gray> <white>" + report.distinctUsernames7d() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- banned:</gray> <white>" + report.banned() + "</white> <gray>remaining(s):</gray> <white>"
                                    + report.banRemainingSeconds()
                                    + "</white> <gray>reason:</gray> <white>"
                                    + report.banReason()
                                    + "</white>"
                    ));
                });
    }

    private void handleRisk(Invocation invocation, String[] args, VelocityLang lang) {
        if (!hasSecurityPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }
        if (args.length < 2 || args[1].isBlank()) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy risk <username>"));
            return;
        }

        String username = args[1];
        securityInspectorSupplier.get()
                .inspectUser(username)
                .whenComplete((report, exception) -> {
                    if (exception != null) {
                        lang.send(invocation.source(), "error-generic");
                        return;
                    }
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>[ProudAuth]</gray> <aqua>User Risk</aqua> <white>" + report.username() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- ips 1h:</gray> <white>" + report.distinctIps1h() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- ips 24h:</gray> <white>" + report.distinctIps24h() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- ips 7d:</gray> <white>" + report.distinctIps7d() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- account:</gray> <white>" + report.accountExists() + "</white> <gray>type:</gray> <white>"
                                    + (report.accountType() == null ? "n/a" : report.accountType().name())
                                    + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- stored uuid:</gray> <white>" + (report.storedUuid() == null ? "n/a" : report.storedUuid()) + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- last ip:</gray> <white>" + report.lastIp() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- premium uuid mismatch:</gray> <white>" + report.premiumUuidMismatch() + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>- risk:</gray> <white>" + report.riskLevel().name() + "</white> <gray>score:</gray> <white>"
                                    + report.riskScore()
                                    + "</white>"
                    ));
                });
    }

    private void handleRiskTop(Invocation invocation, String[] args, VelocityLang lang) {
        if (!hasSecurityPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }

        int limit = 10;
        int hours = 24;
        if (args.length >= 2) {
            try {
                limit = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy risk-top [limit] [hours]"));
                return;
            }
        }
        if (args.length >= 3) {
            try {
                hours = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException exception) {
                lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy risk-top [limit] [hours]"));
                return;
            }
        }
        final int reportLimit = limit;
        final int reportHours = hours;

        securityInspectorSupplier.get()
                .inspectTopRisks(Duration.ofHours(reportHours), reportLimit)
                .whenComplete((report, exception) -> {
                    if (exception != null) {
                        lang.send(invocation.source(), "error-generic");
                        return;
                    }
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>[ProudAuth]</gray> <aqua>Risk Top</aqua> <gray>window:</gray> <white>"
                                    + report.window().toHours()
                                    + "h</white> <gray>limit:</gray> <white>"
                                    + reportLimit
                                    + "</white>"
                    ));
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize("<gray>Top IP by usernames:</gray>"));
                    for (IpHistoryStorage.IpSummary ipSummary : report.topIpsByUserSpread()) {
                        invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                                "<white>"
                                        + ipSummary.ipAddress()
                                        + "</white> <gray>usernames:</gray> <white>"
                                        + ipSummary.distinctUsernames()
                                        + "</white> <gray>hits:</gray> <white>"
                                        + ipSummary.totalHits()
                                        + "</white>"
                        ));
                    }
                    invocation.source().sendMessage(MINI_MESSAGE.deserialize("<gray>Top users by IPs:</gray>"));
                    for (IpHistoryStorage.UserSummary userSummary : report.topUsersByIpSpread()) {
                        invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                                "<white>"
                                        + userSummary.username()
                                        + "</white> <gray>ips:</gray> <white>"
                                        + userSummary.distinctIps()
                                        + "</white> <gray>hits:</gray> <white>"
                                        + userSummary.totalHits()
                                        + "</white>"
                        ));
                    }
                });
    }

    private void handleBanwaveIp(Invocation invocation, String[] args, VelocityLang lang) {
        if (!hasSecurityPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }
        if (args.length < 3) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy banwave-ip <ip> <seconds> [reason]"));
            return;
        }

        String ipAddress = args[1];
        long seconds;
        try {
            seconds = Math.max(1L, Long.parseLong(args[2]));
        } catch (NumberFormatException exception) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy banwave-ip <ip> <seconds> [reason]"));
            return;
        }

        String reason = args.length > 3
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length))
                : "Banwave manuale da console proxy";

        CompletableFuture<Void> action = storageSupplier.get()
                .banIp(ipAddress, Instant.now().plusSeconds(seconds), reason);
        action.whenComplete((ignored, exception) -> {
            if (exception != null) {
                lang.send(invocation.source(), "error-generic");
                return;
            }
            invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                    "<gray>[ProudAuth]</gray> <green>Banwave IP applicata</green> <white>"
                            + ipAddress
                            + "</white> <gray>seconds:</gray> <white>"
                            + seconds
                            + "</white> <gray>reason:</gray> <white>"
                            + reason
                            + "</white>"
            ));
        });
    }

    private void handleExportRiskCsv(Invocation invocation, String[] args, VelocityLang lang) {
        if (!hasSecurityPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }

        int hours = 24;
        int limit = 50;
        if (args.length >= 2) {
            try {
                hours = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException exception) {
                lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy export-risk-csv [hours] [limit]"));
                return;
            }
        }
        if (args.length >= 3) {
            try {
                limit = Math.max(1, Integer.parseInt(args[2]));
            } catch (NumberFormatException exception) {
                lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/paproxy export-risk-csv [hours] [limit]"));
                return;
            }
        }
        final int exportHours = hours;
        final int exportLimit = limit;

        CompletableFuture.supplyAsync(() -> {
            try {
                return csvExporterSupplier.get().export(Duration.ofHours(exportHours), exportLimit);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }).whenComplete((path, exception) -> {
            if (exception != null) {
                lang.send(invocation.source(), "error-generic");
                return;
            }
            Path absolutePath = path.toAbsolutePath();
            invocation.source().sendMessage(MINI_MESSAGE.deserialize(
                    "<gray>[ProudAuth]</gray> <green>CSV export creato</green> <white>"
                            + absolutePath
                            + "</white>"
            ));
        });
    }

    private boolean hasReloadPermission(Invocation invocation) {
        return invocation.source().hasPermission("proudauth.admin.reload");
    }

    private boolean hasSecurityPermission(Invocation invocation) {
        return invocation.source().hasPermission("proudauth.admin.security")
                || invocation.source().hasPermission("proudauth.admin.reload");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasReloadPermission(invocation) || hasSecurityPermission(invocation);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String token = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return filter(List.of("reload", "iphistory", "risk", "risk-top", "banwave-ip", "export-risk-csv"), token);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "iphistory" -> args.length == 2
                    ? filter(List.of("127.0.0.1", "192.168.1.10"), args[1])
                    : List.of();
            case "risk" -> {
                if (args.length == 2 && invocation.source() instanceof Player player) {
                    yield filter(List.of(player.getUsername()), args[1]);
                }
                yield List.of();
            }
            case "risk-top" -> {
                if (args.length == 2) {
                    yield filter(List.of("10", "25", "50"), args[1]);
                }
                if (args.length == 3) {
                    yield filter(List.of("1", "6", "12", "24", "48", "72"), args[2]);
                }
                yield List.of();
            }
            case "banwave-ip" -> {
                if (args.length == 2) {
                    yield filter(List.of("127.0.0.1", "192.168.1.10"), args[1]);
                }
                if (args.length == 3) {
                    yield filter(List.of("300", "900", "3600", "7200"), args[2]);
                }
                if (args.length == 4) {
                    yield filter(List.of("Banwave", "ProxySecurity", "SuspiciousNetwork"), args[3]);
                }
                yield List.of();
            }
            case "export-risk-csv" -> {
                if (args.length == 2) {
                    yield filter(List.of("24", "48", "72"), args[1]);
                }
                if (args.length == 3) {
                    yield filter(List.of("50", "100", "250"), args[2]);
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    private List<String> filter(List<String> source, String token) {
        String lowered = token.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }
}
