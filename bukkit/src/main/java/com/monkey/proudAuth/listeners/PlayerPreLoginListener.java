package com.monkey.proudAuth.listeners;

import com.monkey.proudAuth.auth.BukkitPreLoginService;
import com.monkey.proudAuth.auth.ResolvedLogin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerPreLoginListener implements Listener {

    private final BukkitPreLoginService preLoginService;
    private final Map<UUID, ResolvedLogin> resolvedLogins;

    public PlayerPreLoginListener(BukkitPreLoginService preLoginService) {
        this.preLoginService = preLoginService;
        this.resolvedLogins = new ConcurrentHashMap<>();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        preLoginService.resolve(event).ifPresent(resolvedLogin -> {
            resolvedLogins.put(event.getUniqueId(), resolvedLogin);
            if (!event.getUniqueId().equals(resolvedLogin.uuid())) {
                resolvedLogins.put(resolvedLogin.uuid(), resolvedLogin);
            }
        });
    }

    public Optional<ResolvedLogin> consume(UUID uuid) {
        ResolvedLogin resolvedLogin = resolvedLogins.remove(uuid);
        if (resolvedLogin == null) {
            return Optional.empty();
        }
        resolvedLogins.entrySet().removeIf(entry -> entry.getValue().equals(resolvedLogin));
        return Optional.of(resolvedLogin);
    }
}
