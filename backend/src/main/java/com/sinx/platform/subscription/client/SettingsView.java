package com.sinx.platform.subscription.client;

import java.util.List;
import java.util.Map;

/**
 * A node's {@code protocol_settings} read by path.
 *
 * A renderer asks for {@code tls_settings.server_name} and wants either the
 * string or nothing at all. The settings are free-form JSON that the admin API
 * validated but did not type, so every read here has to tolerate a missing key,
 * a key of the wrong shape, and a null - and answer "nothing" rather than
 * throwing in the middle of building a config.
 */
final class SettingsView {

    private static final SettingsView EMPTY = new SettingsView(Map.of());

    private final Map<String, ?> values;

    private SettingsView(Map<String, ?> values) {
        this.values = values;
    }

    static SettingsView of(Map<String, Object> values) {
        return values == null || values.isEmpty() ? EMPTY : new SettingsView(values);
    }

    /** The value at a dotted path, or null when any step of it is missing. */
    Object raw(String path) {
        Object current = values;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(segment);
        }
        return current;
    }

    String text(String path) {
        return raw(path) instanceof String value && !value.isBlank() ? value : null;
    }

    String text(String path, String fallback) {
        String value = text(path);
        return value == null ? fallback : value;
    }

    /** The value or its string form when the JSON held a number instead. */
    String scalar(String path) {
        Object value = raw(path);
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        return value == null ? null : String.valueOf(value);
    }

    Integer integer(String path) {
        return raw(path) instanceof Number number ? number.intValue() : null;
    }

    int integer(String path, int fallback) {
        Integer value = integer(path);
        return value == null ? fallback : value;
    }

    boolean flag(String path) {
        Object value = raw(path);
        if (value instanceof Boolean flag) {
            return flag;
        }
        // A node configured before this field became a boolean may still hold
        // it as a number, and "0" is how the original panel wrote false.
        return value instanceof Number number && number.doubleValue() != 0;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> object(String path) {
        return raw(path) instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }

    List<?> list(String path) {
        return raw(path) instanceof List<?> list ? list : List.of();
    }

    /** A list of strings, or the one string when the JSON held a bare value. */
    List<String> strings(String path) {
        Object value = raw(path);
        if (value instanceof String text) {
            return text.isBlank() ? List.of() : List.of(text);
        }
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(String.class::isInstance)
            .map(String.class::cast)
            .toList();
    }

    /**
     * One of several spellings the same field is known by.
     *
     * The original panel's settings arrived from an admin form that spelled a
     * field one way and a node kernel that read it another; this panel's do not
     * always agree with it either.
     */
    String firstOf(String... paths) {
        for (String path : paths) {
            String value = text(path);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
