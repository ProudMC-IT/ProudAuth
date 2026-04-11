package com.monkey.proudAuth.common.lang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.regex.Pattern;

public final class LanguageFileSupport {

    public static final String DEFAULT_LANGUAGE_FILE = "it.yml";

    private static final Pattern SAFE_LANGUAGE_NAME = Pattern.compile("^[A-Za-z0-9_-]+(?:\\.yml)?$");

    private LanguageFileSupport() {
    }

    public static String normalizeConfiguredLanguage(String rawLanguage) {
        if (rawLanguage == null) {
            return DEFAULT_LANGUAGE_FILE;
        }

        String trimmed = rawLanguage.trim();
        if (trimmed.isEmpty() || !SAFE_LANGUAGE_NAME.matcher(trimmed).matches()) {
            return DEFAULT_LANGUAGE_FILE;
        }

        String withExtension = trimmed.toLowerCase(Locale.ROOT).endsWith(".yml")
                ? trimmed
                : trimmed + ".yml";
        return withExtension.toLowerCase(Locale.ROOT);
    }

    public static Optional<Path> findLanguageFile(Path languageDirectory, String fileName) throws IOException {
        if (Files.notExists(languageDirectory)) {
            return Optional.empty();
        }

        try (Stream<Path> stream = Files.list(languageDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(fileName))
                    .findFirst();
        }
    }

    public static String summarizeKeys(Collection<String> keys, int limit) {
        if (keys.isEmpty()) {
            return "(none)";
        }

        String summary = keys.stream()
                .limit(limit)
                .collect(Collectors.joining(", "));

        long remaining = keys.size() - Math.min(keys.size(), limit);
        if (remaining > 0) {
            summary += " (+" + remaining + " more)";
        }
        return summary;
    }
}
