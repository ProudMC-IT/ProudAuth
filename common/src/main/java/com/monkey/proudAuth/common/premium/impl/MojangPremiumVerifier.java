package com.monkey.proudAuth.common.premium.impl;

import com.monkey.proudAuth.common.config.ProudAuthSettings;
import com.monkey.proudAuth.common.premium.PremiumVerifier;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MojangPremiumVerifier implements PremiumVerifier {

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"");
    private static final Pattern NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpClient httpClient;
    private volatile ProudAuthSettings settings;

    public MojangPremiumVerifier(ProudAuthSettings settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofMillis(settings.premium().apiTimeoutMs()))
                .build();
    }

    @Override
    public CompletableFuture<PremiumCheckResult> verify(String username) {
        if (!settings.premium().enabled()) {
            return CompletableFuture.completedFuture(new PremiumCheckResult(false, PremiumVerifier.offlineUuid(username), username));
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + URLEncoder.encode(username, StandardCharsets.UTF_8)))
                .timeout(Duration.ofMillis(settings.premium().apiTimeoutMs()))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .orTimeout(settings.premium().apiTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .thenApply(response -> parseResponse(username, response))
                .exceptionally(exception -> new PremiumCheckResult(false, PremiumVerifier.offlineUuid(username), username));
    }

    @Override
    public void reload(ProudAuthSettings settings) {
        this.settings = settings;
    }

    private PremiumCheckResult parseResponse(String fallbackUsername, HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            return new PremiumCheckResult(false, PremiumVerifier.offlineUuid(fallbackUsername), fallbackUsername);
        }

        String body = response.body();
        Matcher idMatcher = ID_PATTERN.matcher(body);
        Matcher nameMatcher = NAME_PATTERN.matcher(body);
        if (!idMatcher.find()) {
            return new PremiumCheckResult(false, PremiumVerifier.offlineUuid(fallbackUsername), fallbackUsername);
        }

        String compactUuid = idMatcher.group(1);
        String resolvedName = nameMatcher.find() ? nameMatcher.group(1) : fallbackUsername;
        UUID uuid = UUID.fromString(
                compactUuid.replaceFirst(
                        "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                        "$1-$2-$3-$4-$5"
                )
        );
        return new PremiumCheckResult(true, uuid, resolvedName);
    }
}
