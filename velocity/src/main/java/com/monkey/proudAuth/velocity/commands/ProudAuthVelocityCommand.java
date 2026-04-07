package com.monkey.proudAuth.velocity.commands;

import com.monkey.proudAuth.velocity.config.VelocityLang;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ProudAuthVelocityCommand implements SimpleCommand {

    private final Supplier<VelocityLang> langSupplier;
    private final Runnable reloadAction;

    public ProudAuthVelocityCommand(Supplier<VelocityLang> langSupplier, Runnable reloadAction) {
        this.langSupplier = langSupplier;
        this.reloadAction = reloadAction;
    }

    @Override
    public void execute(Invocation invocation) {
        VelocityLang lang = langSupplier.get();
        if (!hasPermission(invocation)) {
            lang.send(invocation.source(), "no-permission");
            return;
        }

        String[] args = invocation.arguments();
        if (args.length == 0) {
            lang.send(invocation.source(), "error-usage", Placeholder.unparsed("usage", "/proudauth reload"));
            return;
        }

        if ("reload".equalsIgnoreCase(args[0])) {
            reloadAction.run();
            lang.send(invocation.source(), "admin-reload");
            return;
        }

        lang.send(invocation.source(), "admin-unknown-subcommand");
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("proudauth.admin.reload");
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            String token = invocation.arguments().length == 0 ? "" : invocation.arguments()[0].toLowerCase(Locale.ROOT);
            return List.of("reload").stream()
                    .filter(option -> option.startsWith(token))
                    .toList();
        }
        return List.of();
    }
}
