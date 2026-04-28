package com.monkey.proudAuth.commands.admin.support;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface AdminSubcommand {

    String name();

    default List<String> aliases() {
        return List.of();
    }

    String permission();

    boolean execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
