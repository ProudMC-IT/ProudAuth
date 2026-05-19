package com.monkey.proudAuth.velocity.commands;

import com.monkey.proudAuth.common.config.ProudAuthNetworkConfig;
import com.monkey.proudAuth.common.storage.DelegatedAccessHistoryDirection;
import com.monkey.proudAuth.common.storage.DelegatedAccessHistoryRecord;
import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.access.VelocityDelegatedAccessService;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class ProudAccessVelocityCommand implements SimpleCommand {

    private final VelocityDelegatedAccessService delegatedAccessService;
    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<ProudAuthNetworkConfig.History> historySettingsSupplier;

    public ProudAccessVelocityCommand(
            VelocityDelegatedAccessService delegatedAccessService,
            Supplier<VelocityLang> langSupplier,
            Supplier<ProudAuthNetworkConfig.History> historySettingsSupplier
    ) {
        this.delegatedAccessService = delegatedAccessService;
        this.langSupplier = langSupplier;
        this.historySettingsSupplier = historySettingsSupplier;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length > 0 && "history".equalsIgnoreCase(args[0])) {
            history(invocation);
            return;
        }

        if (!(invocation.source() instanceof Player player)) {
            langSupplier.get().send(invocation.source(), "access-player-only");
            return;
        }

        if (args.length == 0) {
            usage(player);
            return;
        }
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (!"accessleave".equals(subcommand) && !isAuthenticated(player)) {
            send(player, "access-auth-required");
            return;
        }

        switch (subcommand) {
            case "allowaccess" -> allowAccess(player, args);
            case "accessjoin" -> accessJoin(player, args);
            case "code" -> code(player, args);
            case "fingerprint" -> fingerprint(player);
            case "help" -> help(player);
            case "accessleave" -> accessLeave(player);
            case "revokeaccess" -> revokeAccess(player, args);
            default -> usage(player);
        }
    }

    private void allowAccess(Player player, String[] args) {
        if (args.length != 3) {
            send(player, "access-usage-allow");
            return;
        }
        delegatedAccessService.allowAccess(player, args[1], args[2]).whenComplete((result, exception) -> {
            if (exception != null) {
                send(player, "access-error-allow");
                return;
            }
            switch (result.status()) {
                case ALLOWED -> send(player, "access-allow-created",
                        Placeholder.unparsed("player", result.delegateUsername()),
                        Placeholder.unparsed("code", result.code()),
                        Placeholder.unparsed("fingerprint", result.fingerprint()));
                case OWNER_NOT_AUTHENTICATED -> send(player, "access-owner-auth-required");
                case OWNER_MISSING -> send(player, "access-owner-not-registered");
                case DELEGATE_MISSING, DELEGATE_NOT_REGISTERED -> send(player, "access-delegate-not-registered");
                case FINGERPRINT_REQUIRED -> send(player, "access-fingerprint-required");
                case FINGERPRINT_MISMATCH -> send(player, "access-fingerprint-mismatch");
            }
        });
    }

    private void accessJoin(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "access-usage-join");
            return;
        }
        delegatedAccessService.requestJoin(player, args[1]).whenComplete((result, exception) -> {
            if (exception != null) {
                send(player, "access-error-join");
                return;
            }
            switch (result.status()) {
                case CHALLENGE -> send(player, "access-join-challenge", Placeholder.unparsed("owner", result.ownerUsername()));
                case ACTOR_UNRESOLVED -> send(player, "access-profile-unresolved");
                case ACTOR_NOT_AUTHENTICATED -> send(player, "access-auth-required");
                case ALREADY_IMPERSONATING -> send(player, "access-already-impersonating");
                case OWNER_MISSING -> send(player, "access-owner-missing");
                case OWNER_ONLINE -> send(player, "access-owner-online");
                case SELF_TARGET -> send(player, "access-self-target");
                case GRANT_MISSING -> send(player, "access-grant-missing");
            }
        });
    }

    private void code(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "access-usage-code");
            return;
        }
        VelocityDelegatedAccessService.CompleteResult result = delegatedAccessService.submitCode(player, args[1]);
        switch (result.status()) {
            case STARTED -> send(player, "access-started", Placeholder.unparsed("owner", result.ownerUsername()));
            case NO_CHALLENGE -> send(player, "access-no-challenge");
            case INVALID_CODE -> send(player, "access-invalid-code");
            case TOO_MANY_ATTEMPTS -> send(player, "access-too-many-attempts");
            case OWNER_MISSING -> send(player, "access-owner-missing");
            case PROFILE_KEY_MISMATCH -> send(player, "access-premium-profile-key-mismatch");
        }
    }

    private void fingerprint(Player player) {
        if (!isAuthenticated(player)) {
            send(player, "access-auth-required");
            return;
        }
        delegatedAccessService.fingerprint(player)
                .ifPresentOrElse(
                        value -> send(player, "access-fingerprint", Placeholder.unparsed("fingerprint", value)),
                        () -> send(player, "access-profile-unresolved")
                );
    }

    private void accessLeave(Player player) {
        VelocityDelegatedAccessService.LeaveResult result = delegatedAccessService.leave(player);
        if (!result.success()) {
            send(player, "access-not-impersonating");
            return;
        }
        send(player, "access-left");
    }

    private void revokeAccess(Player player, String[] args) {
        if (args.length != 2) {
            send(player, "access-usage-revoke");
            return;
        }
        delegatedAccessService.revoke(player, args[1]).whenComplete((removed, exception) -> {
            if (exception != null) {
                send(player, "access-error-revoke");
                return;
            }
            send(player, removed ? "access-revoked" : "access-revoke-missing");
        });
    }

    private void usage(Player player) {
        send(player, "access-usage");
    }

    private void help(Player player) {
        for (int index = 1; index <= 11; index++) {
            send(player, "access-help-" + index);
        }
    }

    private void history(Invocation invocation) {
        HistoryRequest request = parseHistoryRequest(invocation);
        if (request.errorKey() != null) {
            langSupplier.get().send(invocation.source(), request.errorKey());
            return;
        }

        delegatedAccessService.history(request.username(), request.direction(), request.page(), historySettingsSupplier.get().pageSize())
                .whenComplete((result, exception) -> {
                    if (exception != null) {
                        langSupplier.get().send(invocation.source(), "access-history-error");
                        return;
                    }
                    if (!result.found()) {
                        langSupplier.get().send(invocation.source(), "access-owner-missing");
                        return;
                    }
                    renderHistory(invocation.source(), request, result);
                });
    }

    private HistoryRequest parseHistoryRequest(Invocation invocation) {
        CommandSource source = invocation.source();
        if (source instanceof Player playerSource && !isAuthenticated(playerSource)) {
            return HistoryRequest.error("access-auth-required");
        }
        String[] args = invocation.arguments();
        int page = 1;
        String username = null;
        DelegatedAccessHistoryDirection direction = null;
        boolean invalidFilter = false;

        for (int index = 1; index < args.length; index++) {
            String token = args[index];
            if ("--filter".equalsIgnoreCase(token)) {
                if (index + 1 >= args.length) {
                    invalidFilter = true;
                    break;
                }
                Optional<DelegatedAccessHistoryDirection> parsed = DelegatedAccessHistoryDirection.parse(args[++index]);
                if (parsed.isEmpty()) {
                    invalidFilter = true;
                    break;
                }
                direction = parsed.get();
                continue;
            }
            if (token.toLowerCase(Locale.ROOT).startsWith("--filter=")) {
                Optional<DelegatedAccessHistoryDirection> parsed = DelegatedAccessHistoryDirection.parse(token.substring("--filter=".length()));
                if (parsed.isEmpty()) {
                    invalidFilter = true;
                    break;
                }
                direction = parsed.get();
                continue;
            }
            if (isPositiveInteger(token)) {
                page = Math.max(1, Integer.parseInt(token));
                continue;
            }
            username = token;
        }

        if (invalidFilter) {
            return HistoryRequest.error("access-history-invalid-filter");
        }

        if (username == null || username.isBlank()) {
            if (!(source instanceof Player player)) {
                return HistoryRequest.error("access-history-player-required");
            }
            if (!isAuthenticated(player)) {
                return HistoryRequest.error("access-auth-required");
            }
            username = player.getUsername();
        } else if (!canViewHistory(source, username)) {
            return HistoryRequest.error("access-history-admin-required");
        }

        return new HistoryRequest(username, page, direction, null);
    }

    private boolean canViewHistory(CommandSource source, String username) {
        if (source instanceof Player player && player.getUsername().equalsIgnoreCase(username)) {
            return isAuthenticated(player);
        }
        return source.hasPermission("proudauth.access.history.admin") || source.hasPermission("proudauth.admin");
    }

    private void renderHistory(CommandSource source, HistoryRequest request, VelocityDelegatedAccessService.HistoryPageResult result) {
        VelocityLang lang = langSupplier.get();
        int totalPages = result.totalPages();
        int page = Math.min(request.page(), totalPages);
        String filter = request.direction() == null ? "ALL" : request.direction().name();

        source.sendMessage(lang.message("access-history-header",
                Placeholder.unparsed("player", result.account().username()),
                Placeholder.unparsed("page", String.valueOf(page)),
                Placeholder.unparsed("pages", String.valueOf(totalPages)),
                Placeholder.unparsed("filter", filter)));

        if (result.rows().isEmpty()) {
            source.sendMessage(lang.message("access-history-empty"));
        } else {
            DateTimeFormatter formatter = formatter();
            UUID accountUuid = result.account().uuid();
            for (DelegatedAccessHistoryRecord row : result.rows()) {
                String direction = row.actorUuid() != null && row.actorUuid().equals(accountUuid) ? "OUT" : "IN";
                source.sendMessage(lang.message("access-history-row",
                        Placeholder.unparsed("time", formatter.format(row.createdAt())),
                        Placeholder.unparsed("direction", label("access-history-direction-", direction)),
                        Placeholder.unparsed("actor", row.actorUsername()),
                        Placeholder.unparsed("target", row.targetUsername()),
                        Placeholder.unparsed("event", label("access-history-event-", row.eventType())),
                        Placeholder.unparsed("result", label("access-history-result-", row.result())),
                        Placeholder.unparsed("reason", row.reason() == null || row.reason().isBlank() ? "-" : label("access-history-reason-", row.reason()))));
            }
        }

        source.sendMessage(navigation(request, page, totalPages));
    }

    private Component navigation(HistoryRequest request, int page, int totalPages) {
        Component previous = langSupplier.get().rawMessage("access-history-prev");
        if (page > 1) {
            previous = previous
                    .clickEvent(ClickEvent.runCommand(historyCommand(request, page - 1)))
                    .hoverEvent(HoverEvent.showText(langSupplier.get().rawMessage("access-history-prev-hover")));
        }
        Component next = langSupplier.get().rawMessage("access-history-next");
        if (page < totalPages) {
            next = next
                    .clickEvent(ClickEvent.runCommand(historyCommand(request, page + 1)))
                    .hoverEvent(HoverEvent.showText(langSupplier.get().rawMessage("access-history-next-hover")));
        }
        return previous.append(Component.text(" ")).append(next);
    }

    private String historyCommand(HistoryRequest request, int page) {
        StringBuilder command = new StringBuilder("/paaccess history ").append(page);
        command.append(" ").append(request.username());
        if (request.direction() != null) {
            command.append(" --filter ").append(request.direction().name());
        }
        return command.toString();
    }

    private String label(String prefix, String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String key = prefix + raw.toLowerCase(Locale.ROOT).replace('_', '-');
        return langSupplier.get().exportMessages().getOrDefault(key, raw);
    }

    private DateTimeFormatter formatter() {
        ProudAuthNetworkConfig.History history = historySettingsSupplier.get();
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(history.timezone());
        } catch (Exception exception) {
            zoneId = ZoneId.of("Europe/Rome");
        }
        try {
            return DateTimeFormatter.ofPattern(history.timeFormat()).withZone(zoneId);
        } catch (IllegalArgumentException exception) {
            return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zoneId);
        }
    }

    private boolean isPositiveInteger(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            if (!Character.isDigit(token.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private void send(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... resolvers) {
        langSupplier.get().send(player, key, resolvers);
    }

    private boolean isAuthenticated(Player player) {
        return delegatedAccessService.isAuthenticated(player);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String token = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return filter(List.of("help", "fingerprint", "allowAccess", "accessJoin", "code", "accessLeave", "revokeAccess", "history"), token);
        }
        if (args.length >= 2 && "history".equalsIgnoreCase(args[0])) {
            String previous = args[args.length - 2];
            String token = args[args.length - 1];
            if ("--filter".equalsIgnoreCase(previous)) {
                return filter(List.of("OUT", "IN"), token);
            }
            if (token.startsWith("--")) {
                return filter(List.of("--filter"), token);
            }
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String token) {
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(token.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private record HistoryRequest(String username, int page, DelegatedAccessHistoryDirection direction, String errorKey) {
        private static HistoryRequest error(String errorKey) {
            return new HistoryRequest("", 1, null, errorKey);
        }
    }
}
