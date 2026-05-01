package com.monkey.proudAuth.commands.admin.subcommands;

import com.monkey.proudAuth.commands.admin.support.AdminCommandContext;
import com.monkey.proudAuth.commands.admin.support.AdminCommandSupport;
import com.monkey.proudAuth.commands.admin.support.AdminSubcommand;
import com.monkey.proudAuth.common.model.Session;
import com.monkey.proudAuth.common.storage.AccountRecord;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SessionAdminSubcommand implements AdminSubcommand {

    private final AdminCommandContext context;

    public SessionAdminSubcommand(AdminCommandContext context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "session";
    }

    @Override
    public String permission() {
        return "proudauth.admin.session";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!AdminCommandSupport.ensurePermission(sender, permission(), context.langConfig())) {
            return true;
        }
        if (args.length < 2) {
            AdminCommandSupport.sendUsage(sender, context.langConfig(), "/proudauth session <list|clear|clearip> <player|ip>");
            return true;
        }

        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "list" -> handleList(sender, args[1]);
            case "clear" -> handleClear(sender, args[1]);
            case "clearip" -> handleClearIp(sender, args[1]);
            default -> {
                context.langConfig().send(sender, "admin-unknown-subcommand");
                yield true;
            }
        };
    }

    private boolean handleList(CommandSender sender, String username) {
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().findAccountByUsername(username)
                        .thenCompose(optionalAccount -> optionalAccount.isEmpty()
                                ? CompletableFuture.completedFuture(List.<Session>of())
                                : context.storage().listSessions(optionalAccount.get().uuid(), true)),
                (sessions, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (sessions.isEmpty()) {
                        AdminCommandSupport.sendMini(sender,
                                "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <yellow>Nessuna sessione attiva trovata per</yellow> <white>" + username + "</white>.");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <aqua>Sessioni attive per</aqua> <white>" + username + "</white>");
                    for (Session session : sessions) {
                        AdminCommandSupport.sendMini(sender,
                                "<gray>token:</gray> <white>" + session.token().substring(0, Math.min(12, session.token().length())) + "...</white> "
                                        + "<gray>ip:</gray> <white>" + session.ip() + "</white> "
                                        + "<gray>type:</gray> <white>" + session.accountType() + "</white> "
                                        + "<gray>ttl:</gray> <white>" + AdminCommandSupport.formatRemaining(session.expiresAt()) + "</white>");
                    }
                }
        );
        return true;
    }

    private boolean handleClear(CommandSender sender, String username) {
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.authService().findAccountByUsername(username)
                        .thenCompose(optionalAccount -> optionalAccount.isEmpty()
                                ? CompletableFuture.completedFuture(Optional.<AccountRecord>empty())
                                : context.sessionManager().invalidate(optionalAccount.get().uuid()).thenApply(ignored -> optionalAccount)),
                (optionalAccount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    if (optionalAccount.isEmpty()) {
                        context.langConfig().send(sender, "admin-player-not-found",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", username));
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Sessioni invalidate per</green> <white>" + username + "</white>.");
                }
        );
        return true;
    }

    private boolean handleClearIp(CommandSender sender, String ipAddress) {
        if (!AdminCommandSupport.isLikelyIpAddress(ipAddress)) {
            context.langConfig().send(sender, "admin-invalid-ip",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("ip", ipAddress));
            return true;
        }
        AdminCommandSupport.handleFuture(
                context.plugin(),
                context.storage().deleteSessionsByIp(ipAddress),
                (removedCount, exception) -> {
                    if (exception != null) {
                        context.langConfig().send(sender, "error-generic");
                        return;
                    }
                    AdminCommandSupport.sendMini(sender,
                            "<dark_gray>[<aqua>ProudAuth</aqua>]</dark_gray> <green>Sessioni invalide per IP</green> <white>" + ipAddress
                                    + "</white> <gray>(rimosse:</gray> <white>" + removedCount + "</white><gray>)</gray>.");
                }
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return AdminCommandSupport.filter(List.of("list", "clear", "clearip"), args[0]);
        }
        if (args.length == 2 && ("list".equalsIgnoreCase(args[0]) || "clear".equalsIgnoreCase(args[0]))) {
            return AdminCommandSupport.playerNameSuggestions(context.storage(), args[1]);
        }
        if (args.length == 2 && "clearip".equalsIgnoreCase(args[0])) {
            return AdminCommandSupport.filter(AdminCommandSupport.onlineIps(), args[1]);
        }
        return List.of();
    }
}
