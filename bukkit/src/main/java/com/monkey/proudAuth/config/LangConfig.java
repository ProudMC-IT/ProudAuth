package com.monkey.proudAuth.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Objects;

public final class LangConfig {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage;
    private volatile YamlConfiguration configuration;

    public LangConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        reload();
    }

    public void reload() {
        File langDirectory = new File(plugin.getDataFolder(), "lang");
        if (!langDirectory.exists() && !langDirectory.mkdirs()) {
            plugin.getLogger().warning("Impossibile creare la cartella lang.");
        }

        File langFile = new File(langDirectory, "it.yml");
        if (!langFile.exists()) {
            plugin.saveResource("lang/it.yml", false);
        }

        try {
            this.configuration = YamlConfiguration.loadConfiguration(langFile);
        } catch (Exception exception) {
            plugin.getLogger().severe("Impossibile caricare lang/it.yml: " + exception.getMessage());
            this.configuration = new YamlConfiguration();
        }
    }

    public Component message(String key, TagResolver... resolvers) {
        String body = configuration.getString(key, key);
        String prefix = configuration.getString("prefix", "");
        String payload = Objects.equals(key, "prefix") ? body : prefix + body;
        return miniMessage.deserialize(payload, resolvers);
    }

    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        sender.sendMessage(message(key, resolvers));
    }
}
