package com.monkey.proudAuth.velocity.compat;

import com.velocitypowered.api.event.connection.PreLoginEvent;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class ProudXPreLoginResults {

    private ProudXPreLoginResults() {
    }

    public static boolean isTryKeyAuthenticationAvailable() {
        try {
            PreLoginEvent.PreLoginComponentResult.class.getMethod("tryKeyAuthentication", UUID.class);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    public static Optional<PreLoginEvent.PreLoginComponentResult> tryKeyAuthentication() {
        try {
            Method method = PreLoginEvent.PreLoginComponentResult.class.getMethod("tryKeyAuthentication");
            Object result = method.invoke(null);
            if (result instanceof PreLoginEvent.PreLoginComponentResult componentResult) {
                return Optional.of(componentResult);
            }
        } catch (ReflectiveOperationException ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    public static Optional<PreLoginEvent.PreLoginComponentResult> tryKeyAuthentication(UUID expectedProfileUuid) {
        if (expectedProfileUuid == null) {
            return tryKeyAuthentication();
        }
        try {
            Method method = PreLoginEvent.PreLoginComponentResult.class.getMethod(
                    "tryKeyAuthentication",
                    UUID.class
            );
            Object result = method.invoke(null, expectedProfileUuid);
            if (result instanceof PreLoginEvent.PreLoginComponentResult componentResult) {
                return Optional.of(componentResult);
            }
        } catch (ReflectiveOperationException ignored) {
            return tryKeyAuthentication();
        }
        return Optional.empty();
    }
}
