package com.monkey.proudAuth.update;

public record UpdateCheckResult(
        boolean checked,
        boolean updateAvailable,
        String currentVersion,
        String latestVersion,
        int resourceId,
        String resourceUrl,
        String error
) {

    public static UpdateCheckResult notChecked(String currentVersion, int resourceId) {
        return new UpdateCheckResult(
                false,
                false,
                currentVersion,
                currentVersion,
                resourceId,
                buildResourceUrl(resourceId),
                ""
        );
    }

    public static UpdateCheckResult success(String currentVersion, String latestVersion, int resourceId, boolean updateAvailable) {
        return new UpdateCheckResult(
                true,
                updateAvailable,
                currentVersion,
                latestVersion,
                resourceId,
                buildResourceUrl(resourceId),
                ""
        );
    }

    public static UpdateCheckResult failed(String currentVersion, int resourceId, String error) {
        return new UpdateCheckResult(
                true,
                false,
                currentVersion,
                currentVersion,
                resourceId,
                buildResourceUrl(resourceId),
                error == null ? "unknown" : error
        );
    }

    private static String buildResourceUrl(int resourceId) {
        return "https://www.spigotmc.org/resources/" + resourceId;
    }
}
