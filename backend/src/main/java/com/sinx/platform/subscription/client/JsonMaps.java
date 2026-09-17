package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * Reading the JSON columns a node stores.
 *
 * A column that cannot be read yields nothing rather than an exception. That is
 * a deliberate choice made once, here: a config is assembled from a handful of
 * these, and one unreadable node should cost the customer that node, not the
 * whole config. The control plane makes the opposite choice, and is right to -
 * it turns the same bytes into the node's own settings, where guessing is worse
 * than refusing.
 */
final class JsonMaps {

    private JsonMaps() {
    }

    static Map<String, Object> parseObject(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            if (!(mapper.readValue(json, Map.class) instanceof Map<?, ?> parsed)) {
                return Map.of();
            }
            Map<String, Object> values = new LinkedHashMap<>();
            parsed.forEach((key, value) -> values.put(String.valueOf(key), value));
            return values;
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }
}
