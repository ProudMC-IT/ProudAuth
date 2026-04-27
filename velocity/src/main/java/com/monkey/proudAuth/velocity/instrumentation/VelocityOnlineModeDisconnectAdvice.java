package com.monkey.proudAuth.velocity.instrumentation;

import net.bytebuddy.asm.Advice;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Objects;

public final class VelocityOnlineModeDisconnectAdvice {

    private static final String ONLINE_MODE_ONLY_KEY = "velocity.error.online-mode-only";
    private static volatile Component customDisconnectMessage = defaultMessage();

    private VelocityOnlineModeDisconnectAdvice() {
    }

    public static void updateMessage(String miniMessage) {
        try {
            String resolved = miniMessage == null || miniMessage.isBlank()
                    ? defaultMiniMessage()
                    : miniMessage;
            customDisconnectMessage = MiniMessage.miniMessage().deserialize(resolved);
        } catch (Throwable ignored) {
            customDisconnectMessage = defaultMessage();
        }
    }

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(value = 0, readOnly = false) Component reason) {
        if (!(reason instanceof TranslatableComponent translatable)) {
            return;
        }
        if (!ONLINE_MODE_ONLY_KEY.equals(translatable.key())) {
            return;
        }
        reason = Objects.requireNonNullElseGet(customDisconnectMessage, VelocityOnlineModeDisconnectAdvice::defaultMessage);
    }

    private static Component defaultMessage() {
        return MiniMessage.miniMessage().deserialize(defaultMiniMessage());
    }

    private static String defaultMiniMessage() {
        return "<dark_gray>[</dark_gray><aqua>ProudMC</aqua><dark_gray>] </dark_gray>"
                + "<red>Accesso rifiutato</red><newline>"
                + "<gray>Questo nickname premium richiede un account Minecraft premium autenticato.</gray>";
    }
}
