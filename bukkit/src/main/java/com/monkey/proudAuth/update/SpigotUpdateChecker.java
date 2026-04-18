package com.monkey.proudAuth.update;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public final class SpigotUpdateChecker {

    private final Executor executor;
    private final HttpClient httpClient;

    public SpigotUpdateChecker(Executor executor) {
        this.executor = executor;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public CompletableFuture<UpdateCheckResult> check(int resourceId, String currentVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.spigotmc.org/legacy/update.php?resource=" + resourceId))
                        .header("User-Agent", "ProudAuth-UpdateChecker")
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    return UpdateCheckResult.failed(currentVersion, resourceId, "http_status_" + response.statusCode());
                }
                String latestVersion = response.body() == null ? "" : response.body().trim();
                if (latestVersion.isBlank()) {
                    return UpdateCheckResult.failed(currentVersion, resourceId, "empty_response");
                }
                boolean updateAvailable = isLatestVersionNewer(currentVersion, latestVersion);
                return UpdateCheckResult.success(currentVersion, latestVersion, resourceId, updateAvailable);
            } catch (Exception exception) {
                return UpdateCheckResult.failed(currentVersion, resourceId, exception.getClass().getSimpleName());
            }
        }, executor);
    }

    private boolean isLatestVersionNewer(String currentVersion, String latestVersion) {
        if (latestVersion.equalsIgnoreCase(currentVersion)) {
            return false;
        }
        int comparison = compareVersions(latestVersion, currentVersion);
        if (comparison > 0) {
            return true;
        }
        if (comparison < 0) {
            return false;
        }
        return !latestVersion.equalsIgnoreCase(currentVersion);
    }

    private int compareVersions(String leftRaw, String rightRaw) {
        List<String> left = tokenizeVersion(leftRaw);
        List<String> right = tokenizeVersion(rightRaw);
        int max = Math.max(left.size(), right.size());
        for (int i = 0; i < max; i++) {
            String l = i < left.size() ? left.get(i) : "0";
            String r = i < right.size() ? right.get(i) : "0";
            int tokenComparison = compareVersionToken(l, r);
            if (tokenComparison != 0) {
                return tokenComparison;
            }
        }
        return 0;
    }

    private int compareVersionToken(String left, String right) {
        Integer leftNumber = parseInteger(left);
        Integer rightNumber = parseInteger(right);
        if (leftNumber != null && rightNumber != null) {
            return Integer.compare(leftNumber, rightNumber);
        }
        if (leftNumber != null) {
            return 1;
        }
        if (rightNumber != null) {
            return -1;
        }
        return left.toLowerCase(Locale.ROOT).compareTo(right.toLowerCase(Locale.ROOT));
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private List<String> tokenizeVersion(String raw) {
        String[] split = raw.split("[^A-Za-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String value : split) {
            if (!value.isBlank()) {
                tokens.add(value);
            }
        }
        if (tokens.isEmpty()) {
            tokens.add(raw);
        }
        return tokens;
    }
}
