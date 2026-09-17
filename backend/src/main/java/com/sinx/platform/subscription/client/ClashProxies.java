package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code proxies} entries the Clash family understands.
 *
 * The original keeps two builders - a narrow one for {@code clash} and a wide
 * one for the mihomo forks - and they disagree about more than the protocol
 * list. The narrow one writes {@code tls: true} where the wide one writes the
 * mode itself, understands three transports where the wide one understands
 * five, and spells the shadowsocks plugin options differently. The differences
 * are kept rather than merged, because a client that only understands the
 * narrow set has no use for the wider one's fields and vice versa.
 *
 * Fields whose value is absent are left out rather than written as YAML null.
 * The original emits a handful of nulls - {@code sni}, {@code up}, {@code down}
 * on a hysteria node with no bandwidth set - which no client needs and which
 * read as mistakes in an admin's diff.
 */
final class ClashProxies {

    private ClashProxies() {
    }

    // ------------------------------------------------------------------
    // Shadowsocks
    // ------------------------------------------------------------------

    static Map<String, Object> shadowsocks(NodeClientView node, boolean meta) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "ss");
        proxy.put("cipher", settings.text("cipher"));
        proxy.put("password", node.credential());
        proxy.put("udp", true);
        appendShadowsocksPlugin(proxy, settings, meta);
        return proxy;
    }

    /**
     * The shadowsocks plugin stanza.
     *
     * Written twice in the original with different rules, and the differences
     * are load-bearing: the narrow one drops any option that is not a
     * {@code key=value} pair while the wide one keeps it as a flag, the narrow
     * one reads an obfs host from a key the wide one does not, and the wide one
     * folds {@code obfs-local} into {@code obfs} and knows two plugins the
     * narrow one has never heard of.
     */
    private static void appendShadowsocksPlugin(
        Map<String, Object> proxy,
        SettingsView settings,
        boolean meta
    ) {
        String plugin = settings.text("plugin");
        String pluginOpts = settings.text("plugin_opts");
        if (plugin == null || pluginOpts == null) {
            return;
        }
        Map<String, Object> parsed = parsePluginOptions(pluginOpts, meta);
        proxy.put("plugin", meta && "obfs-local".equals(plugin) ? "obfs" : plugin);
        proxy.put("plugin-opts", switch (plugin) {
            case "obfs", "obfs-local" -> obfsOptions(parsed, settings, meta);
            case "v2ray-plugin" -> v2rayPluginOptions(parsed, meta);
            case "shadow-tls" -> meta ? shadowTlsOptions(parsed) : parsed;
            case "restls" -> meta ? restlsOptions(parsed) : parsed;
            default -> parsed;
        });
    }

    /**
     * {@code obfs=...;obfs-host=...} into a map.
     *
     * A pair with no {@code =} is a boolean flag to the wide builder and noise
     * to the narrow one, which is the whole reason the two cannot share this.
     */
    private static Map<String, Object> parsePluginOptions(String options, boolean meta) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String pair : options.split(";", -1)) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator < 0) {
                if (meta) {
                    parsed.put(pair.trim(), true);
                }
                continue;
            }
            parsed.put(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
        }
        return parsed;
    }

    private static Map<String, Object> obfsOptions(
        Map<String, Object> parsed,
        SettingsView settings,
        boolean meta
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", meta
            ? firstNonNull(parsed.get("obfs"), parsed.get("mode"), "http")
            : firstNonNull(parsed.get("obfs"), settings.text("obfs"), "http"));
        options.put("host", meta
            ? firstNonNull(parsed.get("obfs-host"), parsed.get("host"), "www.bing.com")
            : firstNonNull(parsed.get("obfs-host"), settings.text("obfs_settings.host"), ""));
        if (!meta) {
            // The wide builder has no path option for obfs and would leak an
            // unrecognised key into the config if it kept one.
            copyIfPresent(options, parsed, "path");
            return options;
        }
        return withoutNulls(options);
    }

    private static Map<String, Object> v2rayPluginOptions(
        Map<String, Object> parsed,
        boolean meta
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", firstNonNull(parsed.get("mode"), "websocket"));
        options.put(
            "tls",
            meta
                ? parsed.containsKey("tls") || parsed.containsKey("server")
                : "true".equals(parsed.get("tls"))
        );
        options.put("host", firstNonNull(parsed.get("host"), ""));
        options.put("path", firstNonNull(parsed.get("path"), "/"));
        if (!meta) {
            return options;
        }
        if (parsed.containsKey("mux")) {
            options.put("mux", true);
        }
        if (parsed.containsKey("host")) {
            options.put("headers", Map.of("Host", String.valueOf(parsed.get("host"))));
        }
        return withoutNulls(options);
    }

    private static Map<String, Object> shadowTlsOptions(Map<String, Object> parsed) {
        Map<String, Object> options = new LinkedHashMap<>();
        copyIfPresent(options, parsed, "host");
        copyIfPresent(options, parsed, "password");
        options.put("version", parsed.containsKey("version")
            ? Integer.parseInt(String.valueOf(parsed.get("version")))
            : 2);
        return options;
    }

    private static Map<String, Object> restlsOptions(Map<String, Object> parsed) {
        Map<String, Object> options = new LinkedHashMap<>();
        copyIfPresent(options, parsed, "host");
        copyIfPresent(options, parsed, "password");
        options.put("restls-script", parsed.getOrDefault("restls-script", "123"));
        return options;
    }

    // ------------------------------------------------------------------
    // vmess
    // ------------------------------------------------------------------

    static Map<String, Object> vmess(NodeClientView node, boolean meta) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "vmess");
        proxy.put("uuid", node.credential());
        proxy.put("alterId", 0);
        proxy.put("cipher", "auto");
        proxy.put("udp", true);

        if (settings.flag("tls")) {
            proxy.put("tls", !meta || settings.integer("tls", 1) != 0);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            putIfPresent(proxy, "servername", settings.text("tls_settings.server_name"));
            if (meta) {
                TlsOptions.appendClashEch(proxy, settings, "tls_settings.ech");
            }
        }
        if (meta) {
            appendUtls(proxy, settings, node.name());
            appendMultiplex(proxy, settings);
        }

        appendTransport(proxy, settings, meta, node.host(), false);
        return proxy;
    }

    // ------------------------------------------------------------------
    // vless and trojan
    // ------------------------------------------------------------------

    static Map<String, Object> vless(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "vless");
        proxy.put("uuid", node.credential());
        proxy.put("alterId", 0);
        proxy.put("cipher", "auto");
        proxy.put("udp", true);
        putIfPresent(proxy, "flow", settings.text("flow"));
        proxy.put("encryption", settings.flag("encryption.enabled")
            ? settings.text("encryption.encryption", "none")
            : "none");
        proxy.put("tls", false);

        switch (settings.integer("tls", 0)) {
            case 1 -> {
                proxy.put("tls", true);
                proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
                putIfPresent(proxy, "servername", settings.text("tls_settings.server_name"));
                TlsOptions.appendClashEch(proxy, settings, "tls_settings.ech");
                appendUtls(proxy, settings, node.name());
            }
            case 2 -> {
                proxy.put("tls", true);
                proxy.put("skip-cert-verify", settings.flag("reality_settings.allow_insecure"));
                putIfPresent(proxy, "servername", settings.text("reality_settings.server_name"));
                proxy.put("reality-opts", realityOptions(settings));
                appendUtls(proxy, settings, node.name());
            }
            default -> {
            }
        }

        appendTransport(proxy, settings, true, node.host(), false);
        appendMultiplex(proxy, settings);
        return proxy;
    }

    static Map<String, Object> trojan(NodeClientView node, boolean meta) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "trojan");
        proxy.put("password", node.credential());
        proxy.put("udp", true);

        if (!meta) {
            putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            appendTransport(proxy, settings, false, node.host(), true);
            return proxy;
        }

        if (settings.integer("tls", 1) == 2) {
            proxy.put("skip-cert-verify", settings.flag("reality_settings.allow_insecure"));
            putIfPresent(proxy, "sni", settings.text("reality_settings.server_name"));
            proxy.put("reality-opts", realityOptions(settings));
        } else {
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            TlsOptions.appendClashEch(proxy, settings, "tls_settings.ech");
        }
        appendUtls(proxy, settings, node.name());
        appendMultiplex(proxy, settings);
        appendTransport(proxy, settings, true, node.host(), true);
        return proxy;
    }

    // ------------------------------------------------------------------
    // The plain-credential protocols
    // ------------------------------------------------------------------

    static Map<String, Object> socks(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "socks5");
        proxy.put("udp", true);
        proxy.put("username", node.credential());
        proxy.put("password", node.credential());
        if (settings.flag("tls")) {
            proxy.put("tls", true);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
        }
        return proxy;
    }

    static Map<String, Object> http(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "http");
        proxy.put("username", node.credential());
        proxy.put("password", node.credential());
        if (settings.flag("tls")) {
            proxy.put("tls", true);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
        }
        return proxy;
    }

    // ------------------------------------------------------------------
    // The mihomo-only protocols
    // ------------------------------------------------------------------

    static Map<String, Object> hysteria(NodeClientView node) {
        SettingsView settings = node.settings();
        boolean version2 = settings.integer("version", 1) == 2;
        Map<String, Object> proxy = base(node, version2 ? "hysteria2" : "hysteria");
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        putIfPresent(proxy, "up", settings.integer("bandwidth.up"));
        putIfPresent(proxy, "down", settings.integer("bandwidth.down"));
        proxy.put("skip-cert-verify", settings.flag("tls.allow_insecure"));
        // The original writes this only when the node set one, and a hop
        // interval of zero would read as "rotate the port constantly".
        Integer hopInterval = settings.integer("hop_interval");
        if (hopInterval != null && hopInterval != 0) {
            proxy.put("hop-interval", hopInterval);
        }

        if (version2) {
            proxy.put("password", node.credential());
            if (settings.flag("obfs.open")) {
                putIfPresent(proxy, "obfs", settings.text("obfs.type"));
                putIfPresent(proxy, "obfs-password", settings.text("obfs.password"));
            }
        } else {
            proxy.put("auth_str", node.credential());
            proxy.put("protocol", "udp");
            putIfPresent(proxy, "obfs", settings.flag("obfs.open")
                ? settings.text("obfs.password")
                : null);
            proxy.put("fast-open", true);
            proxy.put("disable_mtu_discovery", true);
        }
        return proxy;
    }

    static Map<String, Object> tuic(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "tuic");
        proxy.put("udp", true);
        if (settings.integer("version", 5) == 4) {
            proxy.put("token", node.credential());
        } else {
            proxy.put("uuid", node.credential());
            proxy.put("password", node.credential());
        }
        proxy.put("skip-cert-verify", settings.flag("tls.allow_insecure"));
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        List<String> alpn = settings.strings("alpn");
        if (!alpn.isEmpty()) {
            proxy.put("alpn", alpn);
        }
        proxy.put("congestion-controller", settings.text("congestion_control", "cubic"));
        proxy.put("udp-relay-mode", settings.text("udp_relay_mode", "native"));
        return proxy;
    }

    static Map<String, Object> anytls(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "anytls");
        proxy.put("password", node.credential());
        proxy.put("udp", true);
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        if (settings.flag("tls.allow_insecure")) {
            proxy.put("skip-cert-verify", true);
        }
        TlsOptions.appendClashEch(proxy, settings, "tls.ech");
        return proxy;
    }

    static Map<String, Object> mieru(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "mieru");
        proxy.put("username", node.credential());
        proxy.put("password", node.credential());
        proxy.put("transport", settings.text("transport", "TCP").toUpperCase(Locale.ROOT));
        return proxy;
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    /** The fields every proxy entry starts with; the type follows for most. */
    private static Map<String, Object> base(NodeClientView node, String type) {
        Map<String, Object> proxy = new LinkedHashMap<>();
        proxy.put("name", node.name());
        proxy.put("type", type);
        proxy.put("server", node.host());
        proxy.put("port", node.port());
        return proxy;
    }

    /**
     * The transport stanza.
     *
     * This panel names the tcp-with-http-header transport {@code tcp-http}
     * where the original spelled it {@code network: tcp} plus a {@code header}
     * object, so that one case is translated rather than copied. It also has no
     * per-node http path or Host header to read - {@code network_settings} here
     * carries path, host, service_name, mode and two node-side flags - so the
     * http options are written with the original's own defaults.
     *
     * @param defaultToTcp whether an unsupported transport falls back to tcp.
     *                     The original's trojan builder does that and its vmess
     *                     builder does not, which is the sort of difference a
     *                     merge would quietly erase.
     */
    private static void appendTransport(
        Map<String, Object> proxy,
        SettingsView settings,
        boolean meta,
        String nodeHost,
        boolean defaultToTcp
    ) {
        String network = settings.text("network", "tcp");
        switch (network) {
            case "tcp" -> proxy.put("network", "tcp");
            case "tcp-http" -> {
                proxy.put("network", "http");
                proxy.put("http-opts", httpOptions());
            }
            case "ws" -> {
                proxy.put("network", "ws");
                Map<String, Object> options = new LinkedHashMap<>();
                putIfPresent(options, "path", settings.text("network_settings.path"));
                putIfPresent(options, "headers", hostHeader(settings));
                if (!options.isEmpty()) {
                    proxy.put("ws-opts", options);
                }
            }
            case "grpc" -> {
                proxy.put("network", "grpc");
                Map<String, Object> options = new LinkedHashMap<>();
                putIfPresent(options, "grpc-service-name", settings.text("network_settings.service_name"));
                if (!options.isEmpty()) {
                    proxy.put("grpc-opts", options);
                }
            }
            case "h2" -> {
                if (!meta) {
                    fallbackTransport(proxy, defaultToTcp);
                    return;
                }
                proxy.put("network", "h2");
                Map<String, Object> options = new LinkedHashMap<>();
                putIfPresent(options, "path", settings.text("network_settings.path"));
                String host = settings.text("network_settings.host");
                if (host != null) {
                    options.put("host", List.of(host));
                }
                if (!options.isEmpty()) {
                    proxy.put("h2-opts", options);
                }
            }
            case "httpupgrade" -> {
                if (!meta) {
                    fallbackTransport(proxy, defaultToTcp);
                    return;
                }
                // mihomo has no httpupgrade transport; it is a websocket with
                // an upgrade header.
                proxy.put("network", "ws");
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("v2ray-http-upgrade", true);
                putIfPresent(options, "path", settings.text("network_settings.path"));
                options.put(
                    "headers",
                    Map.of("Host", settings.text("network_settings.host", nodeHost))
                );
                proxy.put("ws-opts", options);
            }
            case "xhttp" -> fallbackTransport(proxy, defaultToTcp);
            default -> fallbackTransport(proxy, defaultToTcp);
        }
    }

    private static void fallbackTransport(Map<String, Object> proxy, boolean defaultToTcp) {
        if (defaultToTcp) {
            proxy.put("network", "tcp");
        }
    }

    /**
     * The original reads an http path and Host header off every node. This panel
     * has nowhere to put them, so the http obfs transport is written with the
     * defaults the original would have used for a node with neither set.
     */
    private static Map<String, Object> httpOptions() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("path", List.of("/"));
        return options;
    }

    /**
     * The Host a websocket transport should present.
     *
     * The original kept a nested {@code network_settings.headers.Host} for
     * websockets and a flat {@code network_settings.host} for everything else.
     * This panel has only the flat one, and the node side reads the flat one,
     * so it serves both.
     */
    private static Map<String, Object> hostHeader(SettingsView settings) {
        String host = settings.text("network_settings.host");
        return host == null ? null : Map.of("Host", host);
    }

    private static Map<String, Object> realityOptions(SettingsView settings) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("public-key", settings.text("reality_settings.public_key"));
        options.put("short-id", settings.text("reality_settings.short_id"));
        return options;
    }

    private static void appendUtls(Map<String, Object> proxy, SettingsView settings, String seed) {
        String fingerprint = TlsOptions.fingerprint(settings, seed);
        if (fingerprint != null) {
            proxy.put("client-fingerprint", fingerprint);
        }
    }

    /**
     * smux, in mihomo's spelling.
     *
     * Only {@code max-connections} survives the original's filter, and
     * {@code padding} is written only when true; the stream bounds the settings
     * accept are commented out there and stay out here rather than being
     * smuggled in.
     */
    private static void appendMultiplex(Map<String, Object> proxy, SettingsView settings) {
        if (!settings.flag("multiplex.enabled")) {
            return;
        }
        Map<String, Object> smux = new LinkedHashMap<>();
        smux.put("enabled", true);
        smux.put("protocol", settings.text("multiplex.protocol", "yamux"));
        putIfPresent(smux, "max-connections", settings.integer("multiplex.max_connections"));
        if (settings.flag("multiplex.padding")) {
            smux.put("padding", true);
        }
        if (settings.flag("multiplex.brutal.enabled")) {
            Map<String, Object> brutal = new LinkedHashMap<>();
            brutal.put("enabled", true);
            putIfPresent(brutal, "up", settings.integer("multiplex.brutal.up_mbps"));
            putIfPresent(brutal, "down", settings.integer("multiplex.brutal.down_mbps"));
            smux.put("brutal-opts", brutal);
        }
        proxy.put("smux", smux);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static void copyIfPresent(
        Map<String, Object> target,
        Map<String, Object> source,
        String key
    ) {
        putIfPresent(target, key, source.get(key));
    }

    /** The wide builder's {@code array_filter}, which keeps everything but null. */
    private static Map<String, Object> withoutNulls(Map<String, Object> values) {
        Map<String, Object> kept = new LinkedHashMap<>();
        values.forEach((key, value) -> putIfPresent(kept, key, value));
        return kept;
    }

    private static String firstNonNull(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate != null) {
                return String.valueOf(candidate);
            }
        }
        return null;
    }
}
