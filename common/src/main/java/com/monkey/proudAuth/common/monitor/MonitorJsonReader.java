package com.monkey.proudAuth.common.monitor;

public final class MonitorJsonReader {

    private MonitorJsonReader() {
    }

    public static String string(String json, String key) {
        String token = "\"" + key + "\"";
        int keyIndex = json.indexOf(token);

        if (keyIndex < 0) {
            return "";
        }

        int colonIndex = json.indexOf(':', keyIndex + token.length());
        if (colonIndex < 0) {
            return "";
        }

        int startQuote = json.indexOf('"', colonIndex + 1);
        if (startQuote < 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        boolean escaping = false;

        for (int index = startQuote + 1; index < json.length(); index++) {
            char character = json.charAt(index);

            if (escaping) {
                builder.append(character);
                escaping = false;
                continue;
            }

            if (character == '\\') {
                escaping = true;
                continue;
            }

            if (character == '"') {
                return builder.toString();
            }

            builder.append(character);
        }

        return "";
    }

    public static String objectAfter(String json, String key) {
        String token = "\"" + key + "\"";
        int keyIndex = json.indexOf(token);

        if (keyIndex < 0) {
            return "";
        }

        int start = json.indexOf('{', keyIndex + token.length());
        if (start < 0) {
            return "";
        }

        int depth = 0;
        boolean inString = false;
        boolean escaping = false;

        for (int index = start; index < json.length(); index++) {
            char character = json.charAt(index);

            if (escaping) {
                escaping = false;
                continue;
            }

            if (character == '\\') {
                escaping = true;
                continue;
            }

            if (character == '"') {
                inString = !inString;
                continue;
            }

            if (inString) {
                continue;
            }

            if (character == '{') {
                depth++;
            }

            if (character == '}') {
                depth--;

                if (depth == 0) {
                    return json.substring(start, index + 1);
                }
            }
        }

        return "";
    }
}