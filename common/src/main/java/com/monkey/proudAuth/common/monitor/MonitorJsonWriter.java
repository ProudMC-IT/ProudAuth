package com.monkey.proudAuth.common.monitor;

import java.util.Iterator;
import java.util.Map;

public final class MonitorJsonWriter {

    private MonitorJsonWriter() {
    }

    public static String write(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String string) {
            return "\"" + escape(string) + "\"";
        }

        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }

        if (value instanceof Map<?, ?> map) {
            return writeMap(map);
        }

        if (value instanceof Iterable<?> iterable) {
            return writeIterable(iterable);
        }

        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String writeMap(Map<?, ?> map) {
        StringBuilder builder = new StringBuilder();
        builder.append('{');

        Iterator<? extends Map.Entry<?, ?>> iterator = map.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
            builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"');
            builder.append(':');
            builder.append(write(entry.getValue()));

            if (iterator.hasNext()) {
                builder.append(',');
            }
        }

        builder.append('}');
        return builder.toString();
    }

    private static String writeIterable(Iterable<?> iterable) {
        StringBuilder builder = new StringBuilder();
        builder.append('[');

        Iterator<?> iterator = iterable.iterator();
        while (iterator.hasNext()) {
            builder.append(write(iterator.next()));

            if (iterator.hasNext()) {
                builder.append(',');
            }
        }

        builder.append(']');
        return builder.toString();
    }

    private static String escape(String value) {
        StringBuilder builder = new StringBuilder();

        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);

            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 32) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }

        return builder.toString();
    }
}