package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two TLS knobs every format spells differently.
 *
 * uTLS is a fingerprint string in the URIs, {@code client-fingerprint} in
 * Clash and an {@code utls} object in sing-box; ECH is one object shape in
 * Clash and another in sing-box. Everything both of them need to decide lives
 * here so the three renderers do not each re-read the same settings.
 *
 * One deliberate difference from the original: it hands out a fingerprint for
 * every node, drawing one at random when the node has no uTLS settings at all,
 * so the same subscription returns a different config each time it is fetched.
 * Here uTLS is emitted only when the node enabled it - a node that did not ask
 * for a fingerprint should not get one, and a subscription that changes shape
 * between fetches is hard to reason about. A node that did ask for
 * {@code random} still gets a varied fingerprint, but one chosen from its name
 * so repeated fetches agree.
 */
final class TlsOptions {

    private static final List<String> FINGERPRINTS =
        List.of("chrome", "firefox", "safari", "ios", "edge", "qq");

    private TlsOptions() {
    }

    /** The uTLS fingerprint, or null when the node does not use uTLS. */
    static String fingerprint(SettingsView settings, String seed) {
        if (!settings.flag("utls.enabled")) {
            return null;
        }
        String fingerprint = settings.text("utls.fingerprint", "chrome");
        if (!"random".equals(fingerprint)) {
            return fingerprint;
        }
        int index = Math.floorMod(seed == null ? 0 : seed.hashCode(), FINGERPRINTS.size());
        return FINGERPRINTS.get(index);
    }

    /**
     * ECH in the Clash family's shape: {@code ech-opts}.
     *
     * The config is stripped of whitespace first - mihomo wants the bare base64
     * payload, and an admin pasting the PEM block out of a browser is the
     * common way it arrives.
     */
    static void appendClashEch(Map<String, Object> target, SettingsView settings, String path) {
        if (!settings.flag(path + ".enabled")) {
            return;
        }
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("enable", true);
        String config = mihomoConfig(settings.text(path + ".config"));
        if (config != null) {
            options.put("config", config);
        }
        String queryServerName = settings.text(path + ".query_server_name");
        if (queryServerName != null) {
            options.put("query-server-name", queryServerName);
        }
        target.put("ech-opts", options);
    }

    /**
     * ECH in sing-box's shape.
     *
     * The client is handed the public config only; the key material the node
     * holds never leaves this side.
     */
    static void appendSingBoxEch(Map<String, Object> target, SettingsView settings, String path) {
        if (!settings.flag(path + ".enabled")) {
            return;
        }
        Map<String, Object> ech = new LinkedHashMap<>();
        ech.put("enabled", true);
        String config = settings.text(path + ".config");
        if (config != null) {
            ech.put("config", List.of(config));
        }
        String queryServerName = settings.text(path + ".query_server_name");
        if (queryServerName != null) {
            ech.put("query_server_name", queryServerName);
        }
        target.put("ech", ech);
    }

    /**
     * mihomo's {@code config} value: the base64 payload on its own.
     *
     * Accepts either the bare payload or the PEM block a browser hands out, in
     * which case only the text between the ECH markers is kept.
     */
    private static String mihomoConfig(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }
        String trimmed = config.trim();
        if (trimmed.startsWith("-----BEGIN")) {
            String body = between(trimmed, "-----BEGIN ECH CONFIGS-----", "-----END ECH CONFIGS-----");
            return body == null ? null : stripWhitespace(body);
        }
        return stripWhitespace(trimmed);
    }

    private static String between(String text, String open, String close) {
        int start = text.indexOf(open);
        int end = text.lastIndexOf(close);
        if (start < 0 || end < 0 || end < start) {
            return null;
        }
        return text.substring(start + open.length(), end);
    }

    private static String stripWhitespace(String value) {
        StringBuilder stripped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character)) {
                stripped.append(character);
            }
        }
        return stripped.toString();
    }
}
