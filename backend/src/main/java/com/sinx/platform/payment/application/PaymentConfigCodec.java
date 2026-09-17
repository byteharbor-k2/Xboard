package com.sinx.platform.payment.application;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads and writes a payment method's credentials.
 *
 * The keys are the gateway's own, so nothing here validates them - an unknown
 * key is the gateway's business to ignore or refuse. Values are held as strings
 * because that is what a gateway form field produces and what a signature is
 * computed over.
 */
@Component
public class PaymentConfigCodec {

    private static final TypeReference<Map<String, Object>> OBJECT_MAP =
        new TypeReference<>() {
        };

    private final ObjectMapper objectMapper;

    PaymentConfigCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Never null: an unconfigured method simply has no keys. */
    Map<String, String> read(String config) {
        if (config == null || config.isBlank()) {
            return Map.of();
        }
        Map<String, Object> stored;
        try {
            stored = objectMapper.readValue(config, OBJECT_MAP);
        } catch (RuntimeException unreadable) {
            throw new IllegalStateException(
                "A payment method's configuration could not be read",
                unreadable
            );
        }
        if (stored == null) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        stored.forEach((key, value) -> {
            if (key != null && value != null) {
                values.put(key, value instanceof String text
                    ? text
                    : String.valueOf(value));
            }
        });
        return values;
    }

    String write(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return "{}";
        }
        return objectMapper.writeValueAsString(config);
    }
}
