package com.monkey.proudAuth.velocity.commands;

import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.monkey.proudAuth.velocity.monitor.VelocityMonitorService;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ProudMonitorVelocityCommand implements SimpleCommand {

    private final Supplier<VelocityLang> langSupplier;
    private final Supplier<VelocityMonitorService> monitorServiceSupplier;

    public ProudMonitorVelocityCommand(
            Supplier<VelocityLang> langSupplier,
            Supplier<VelocityMonitorService> monitorServiceSupplier
    ) {
        this.langSupplier = langSupplier;
        this.monitorServiceSupplier = monitorServiceSupplier;
    }

    @Override
    public void execute(Invocation invocation) {
        VelocityLang lang = langSupplier.get();
        String[] args = invocation.arguments();

        if (!hasMonitorPermission(invocation)) {
            lang.sendRaw(invocation.source(), "no-permission");
            return;
        }

        if (args.length == 0) {
            openMonitor(invocation, lang);
            return;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "sessionlist" -> handleSessionList(invocation, lang);
            case "closesession", "stop" -> handleCloseSession(invocation, lang);
            case "restart" -> handleRestart(invocation, lang);
            default -> lang.sendRaw(invocation.source(), "monitor-usage");
        }
    }

    private void openMonitor(Invocation invocation, VelocityLang lang) {
        String createdBy = invocation.source() instanceof Player player
                ? player.getUsername()
                : "Console";

        monitorServiceSupplier.get().open(createdBy).whenComplete((url, exception) -> {
            if (exception != null) {
                lang.sendRaw(invocation.source(), "monitor-open-failed");
                return;
            }

            lang.sendRaw(invocation.source(), "monitor-opened", clickableUrl("url", url));
        });
    }

    private void handleSessionList(Invocation invocation, VelocityLang lang) {
        VelocityMonitorService monitorService = monitorServiceSupplier.get();

        if (!monitorService.active()) {
            lang.sendRaw(invocation.source(), "monitor-no-active-session");
            return;
        }

        lang.sendRaw(invocation.source(), "monitor-session-active-header");
        lang.sendRaw(invocation.source(), "monitor-session-id", Placeholder.unparsed("session", monitorService.currentSessionId()));
        lang.sendRaw(invocation.source(), "monitor-session-created-by", Placeholder.unparsed("created_by", monitorService.currentCreatedBy()));
        lang.sendRaw(invocation.source(), "monitor-session-created-at", Placeholder.unparsed("created_at", String.valueOf(monitorService.currentCreatedAt())));
        lang.sendRaw(invocation.source(), "monitor-session-url", clickableUrl("url", monitorService.currentUrl()));
    }

    private void handleCloseSession(Invocation invocation, VelocityLang lang) {
        boolean closed = monitorServiceSupplier.get().closeSession("CLOSED_BY_STAFF", "Monitor session closed by staff.");

        if (!closed) {
            lang.sendRaw(invocation.source(), "monitor-no-active-session");
            return;
        }

        lang.sendRaw(invocation.source(), "monitor-session-closed");
    }

    private void handleRestart(Invocation invocation, VelocityLang lang) {
        String createdBy = invocation.source() instanceof Player player
                ? player.getUsername()
                : "Console";

        monitorServiceSupplier.get().restart(createdBy).whenComplete((url, exception) -> {
            if (exception != null) {
                lang.sendRaw(invocation.source(), "monitor-restart-failed");
                return;
            }

            lang.sendRaw(invocation.source(), "monitor-restarted", clickableUrl("url", url));
        });
    }

    private boolean hasMonitorPermission(Invocation invocation) {
        return invocation.source().hasPermission("proudauth.admin.monitor")
                || invocation.source().hasPermission("proudauth.admin.reload");
    }

    private TagResolver clickableUrl(String key, String url) {
        return Placeholder.component(
                key,
                Component.text(url)
                        .color(NamedTextColor.AQUA)
                        .decorate(TextDecoration.UNDERLINED)
                        .clickEvent(ClickEvent.openUrl(url))
        );
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return hasMonitorPermission(invocation);
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();

        if (args.length <= 1) {
            String token = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return filter(List.of("sessionlist", "closesession", "restart", "stop"), token);
        }

        return List.of();
    }

    private List<String> filter(List<String> source, String token) {
        String lowered = token.toLowerCase(Locale.ROOT);
        return source.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lowered))
                .toList();
    }
}