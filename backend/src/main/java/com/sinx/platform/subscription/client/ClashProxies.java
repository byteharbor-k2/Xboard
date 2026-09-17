package com.sinx.platform.subscription.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code proxies} entries the Clash family understands.
 *
 * The original keeps three builders - a narrow one for {@code clash}, a wide
 * one for the mihomo forks, and Stash's - and they disagree about more than the
 * protocol list. The narrow one writes {@code tls: true} where the wide one
 * writes the mode itself, understands three transports where the wide one
 * understands five, and spells the shadowsocks plugin options differently.
 *
 * Stash is the awkward one: it reads mihomo's whole protocol set but none of
 * its newer fields, and it renames half of what it does keep - hysteria's
 * {@code up}/{@code auth_str} become {@code up-speed}/{@code auth-str}, tuic
 * grows four timing knobs and loses {@code udp}, and socks and http gain an
 * {@code sni}. The differences are kept rather than merged, because a client
 * that only understands the narrow set has no use for the wider one's fields
 * and vice versa.
 *
 * Fields whose value is absent are left out rather than written as YAML null.
 * The original emits a handful of nulls - a vmess node's {@code ws-opts.path},
 * an anytls node's {@code sni} - which no client needs and which read as
 * mistakes in an admin's diff.
 */
final class ClashProxies {

    private ClashProxies() {
    }

    // ------------------------------------------------------------------
    // Shadowsocks
    // ------------------------------------------------------------------

    static Map<String, Object> shadowsocks(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "ss");
        proxy.put("cipher", settings.text("cipher"));
        proxy.put("password", node.credential());
        proxy.put("udp", true);
        appendShadowsocksPlugin(proxy, settings, dialect);
        return proxy;
    }

    /**
     * The shadowsocks plugin stanza.
     *
     * Written three times in the original with different rules, and the
     * differences are load-bearing. The narrow and Stash builders drop any
     * option that is not a {@code key=value} pair while the wide one keeps it as
     * a flag. Only the narrow one falls back to the node's own {@code obfs} and
     * {@code obfs_settings.host} when the option string does not carry them.
     * And the wide one alone folds {@code obfs-local} into {@code obfs} and
     * knows {@code shadow-tls} and {@code restls}.
     */
    private static void appendShadowsocksPlugin(
        Map<String, Object> proxy,
        SettingsView settings,
        Dialect dialect
    ) {
        String plugin = settings.text("plugin");
        String pluginOpts = settings.text("plugin_opts");
        if (plugin == null || plugin.isBlank() || pluginOpts == null || pluginOpts.isBlank()) {
            return;
        }
        Map<String, Object> parsed = parsePluginOptions(pluginOpts, dialect.isMihomo());
        proxy.put("plugin", dialect.isMihomo() && "obfs-local".equals(plugin)
            ? "obfs"
            : plugin);
        proxy.put("plugin-opts", switch (plugin) {
            // Stash keeps obfs-local under its own name, so it never takes this
            // arm; mihomo rewrites the name above and lands here.
            case "obfs", "obfs-local" -> obfsOptions(parsed, settings, dialect);
            case "v2ray-plugin" -> v2rayPluginOptions(parsed, dialect.isMihomo());
            case "shadow-tls" -> dialect.isMihomo() ? shadowTlsOptions(parsed) : parsed;
            case "restls" -> dialect.isMihomo() ? restlsOptions(parsed) : parsed;
            default -> parsed;
        });
    }

    /**
     * {@code obfs=...;obfs-host=...} into a map.
     *
     * A pair with no {@code =} is a boolean flag to the wide builder and noise
     * to the other two, which is the whole reason they cannot share this.
     */
    private static Map<String, Object> parsePluginOptions(String options, boolean mihomo) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (String pair : options.split(";", -1)) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator < 0) {
                if (mihomo) {
                    parsed.put(pair.trim(), true);
                }
                continue;
            }
            parsed.put(pair.substring(0, separator).trim(), pair.substring(separator + 1).trim());
        }
        return parsed;
    }

    /**
     * The obfs plugin, whose defaults are the one place the narrow and Stash
     * builders disagree about shadowsocks.
     *
     * The narrow builder will invent a mode and a host for a node whose option
     * string does not carry them; Stash writes only what it was given and lets
     * its own defaults stand. That is not tidiness on Stash's part - an empty
     * mode means the client picks, and the narrow builder's {@code www.bing.com}
     * would be this panel overriding that with a host nobody chose.
     */
    private static Map<String, Object> obfsOptions(
        Map<String, Object> parsed,
        SettingsView settings,
        Dialect dialect
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        if (dialect.isMihomo()) {
            options.put("mode", firstNonNull(parsed.get("obfs"), parsed.get("mode"), "http"));
            options.put(
                "host",
                firstNonNull(parsed.get("obfs-host"), parsed.get("host"), "www.bing.com")
            );
            return withoutNulls(options);
        }
        if (dialect.isStash()) {
            putIfPresent(options, "mode", parsed.get("obfs"));
            putIfPresent(options, "host", parsed.get("obfs-host"));
            copyIfPresent(options, parsed, "path");
            return options;
        }
        options.put(
            "mode",
            firstNonNull(parsed.get("obfs"), settings.text("obfs"), "http")
        );
        options.put(
            "host",
            firstNonNull(parsed.get("obfs-host"), settings.text("obfs_settings.host"), "")
        );
        // The wide builder has no path option for obfs and would leak an
        // unrecognised key into the config if it kept one.
        copyIfPresent(options, parsed, "path");
        return options;
    }

    private static Map<String, Object> v2rayPluginOptions(
        Map<String, Object> parsed,
        boolean mihomo
    ) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("mode", firstNonNull(parsed.get("mode"), "websocket"));
        options.put(
            "tls",
            mihomo
                ? parsed.containsKey("tls") || parsed.containsKey("server")
                : "true".equals(parsed.get("tls"))
        );
        if (mihomo) {
            putIfPresent(options, "host", parsed.get("host"));
            options.put("path", firstNonNull(parsed.get("path"), "/"));
            if (parsed.containsKey("mux")) {
                options.put("mux", true);
            }
            if (parsed.containsKey("host")) {
                options.put("headers", Map.of("Host", String.valueOf(parsed.get("host"))));
            }
            return withoutNulls(options);
        }
        options.put("host", firstNonNull(parsed.get("host"), ""));
        options.put("path", firstNonNull(parsed.get("path"), "/"));
        return options;
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

    static Map<String, Object> vmess(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "vmess");
        proxy.put("uuid", node.credential());
        proxy.put("alterId", 0);
        proxy.put("cipher", "auto");
        proxy.put("udp", true);

        if (dialect.isStash()) {
            // Stash writes both of these on every node rather than only when
            // TLS is on. A client reading them on a plaintext node is told
            // explicitly that there is no TLS, which is the same thing as
            // saying nothing - but it is what the original sends.
            proxy.put("tls", settings.flag("tls"));
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            putIfPresent(proxy, "servername", settings.text("tls_settings.server_name"));
        } else if (settings.flag("tls")) {
            proxy.put("tls", !dialect.isMihomo() || settings.integer("tls", 1) != 0);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            putIfPresent(proxy, "servername", settings.text("tls_settings.server_name"));
            if (dialect.isMihomo()) {
                TlsOptions.appendClashEch(proxy, settings, "tls_settings.ech");
            }
        }
        if (dialect.isMihomo()) {
            appendUtls(proxy, settings, node.name());
            appendMultiplex(proxy, settings);
        }

        appendTransport(proxy, settings, dialect, false, true);
        // Stash's h2 is TLS by definition, and it says so; mihomo infers it.
        if (dialect.isStash() && "h2".equals(proxy.get("network"))) {
            proxy.put("tls", true);
        }
        return proxy;
    }

    // ------------------------------------------------------------------
    // vless
    // ------------------------------------------------------------------

    static Map<String, Object> vless(NodeClientView node, Dialect dialect) {
        return dialect.isStash() ? stashVless(node) : mihomoVless(node);
    }

    /**
     * Stash's vless, which is mihomo's with most of it removed.
     *
     * No {@code alterId}, no {@code cipher}, no {@code encryption} - Stash
     * derives all three - no top-level {@code flow}, which it accepts only
     * inside a reality block, and no ECH or multiplex. It does read the uTLS
     * fingerprint, which mihomo also reads, so that much is shared.
     */
    private static Map<String, Object> stashVless(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "vless");
        proxy.put("uuid", node.credential());
        proxy.put("udp", true);

        appendUtls(proxy, settings, node.name());

        switch (settings.integer("tls", 0)) {
            case 1 -> {
                proxy.put("tls", true);
                proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
                putIfPresent(proxy, "servername", settings.text("tls_settings.server_name"));
            }
            case 2 -> {
                proxy.put("tls", true);
                proxy.put("skip-cert-verify", settings.flag("reality_settings.allow_insecure"));
                String serverName = settings.text("reality_settings.server_name");
                if (serverName != null) {
                    proxy.put("servername", serverName);
                    proxy.put("sni", serverName);
                }
                putIfPresent(proxy, "flow", settings.text("flow"));
                proxy.put("reality-opts", realityOptions(settings));
            }
            default -> {
            }
        }

        appendTransport(proxy, settings, Dialect.STASH, false, true);
        return proxy;
    }

    private static Map<String, Object> mihomoVless(NodeClientView node) {
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

        appendTransport(proxy, settings, Dialect.META, false, true);
        appendMultiplex(proxy, settings);
        return proxy;
    }

    // ------------------------------------------------------------------
    // trojan
    // ------------------------------------------------------------------

    static Map<String, Object> trojan(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "trojan");
        proxy.put("password", node.credential());
        proxy.put("udp", true);

        if (dialect == Dialect.CLASH) {
            putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
        } else if (settings.integer("tls", 1) == 2) {
            proxy.put("skip-cert-verify", settings.flag("reality_settings.allow_insecure"));
            putIfPresent(proxy, "sni", settings.text("reality_settings.server_name"));
            proxy.put("reality-opts", realityOptions(settings));
        } else {
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            if (dialect.isMihomo()) {
                TlsOptions.appendClashEch(proxy, settings, "tls_settings.ech");
            }
        }

        if (dialect.isMihomo()) {
            appendUtls(proxy, settings, node.name());
            appendMultiplex(proxy, settings);
        }
        // The narrow builder has no reality and no h2, so an unknown transport
        // is written as tcp there; Stash and mihomo let the key stand absent.
        // Only Stash's trojan reads a tcp header type - the other two write
        // plain tcp and would ignore one.
        appendTransport(proxy, settings, dialect, dialect == Dialect.CLASH, dialect.isStash());
        return proxy;
    }

    // ------------------------------------------------------------------
    // The plain-credential protocols
    // ------------------------------------------------------------------

    static Map<String, Object> socks(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "socks5");
        proxy.put("udp", true);
        proxy.put("username", node.credential());
        proxy.put("password", node.credential());
        if (settings.flag("tls")) {
            proxy.put("tls", true);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            if (dialect.isStash()) {
                putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            }
        }
        return proxy;
    }

    static Map<String, Object> http(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "http");
        proxy.put("username", node.credential());
        proxy.put("password", node.credential());
        if (settings.flag("tls")) {
            proxy.put("tls", true);
            proxy.put("skip-cert-verify", settings.flag("tls_settings.allow_insecure"));
            if (dialect.isStash()) {
                putIfPresent(proxy, "sni", settings.text("tls_settings.server_name"));
            }
        }
        return proxy;
    }

    // ------------------------------------------------------------------
    // The wide-only protocols
    // ------------------------------------------------------------------

    static Map<String, Object> hysteria(NodeClientView node, Dialect dialect) {
        return dialect.isStash() ? stashHysteria(node) : mihomoHysteria(node);
    }

    /**
     * Stash's hysteria, which renames nearly everything mihomo's writes.
     *
     * The bandwidth hints lose their hyphens, the credential key changes with
     * the protocol version and changes to a different name again, and Stash has
     * no port-hopping at all - so the hop interval and mtu knobs mihomo accepts
     * are simply not written.
     */
    private static Map<String, Object> stashHysteria(NodeClientView node) {
        SettingsView settings = node.settings();
        boolean version2 = settings.integer("version", 1) == 2;
        Map<String, Object> proxy = base(node, version2 ? "hysteria2" : "hysteria");
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        putIfPresent(proxy, "up-speed", settings.integer("bandwidth.up"));
        putIfPresent(proxy, "down-speed", settings.integer("bandwidth.down"));
        proxy.put("skip-cert-verify", settings.flag("tls.allow_insecure"));

        if (version2) {
            proxy.put("auth", node.credential());
            proxy.put("fast-open", true);
            if (settings.flag("obfs.open")) {
                proxy.put("obfs", settings.text("obfs.type", "salamander"));
                putIfPresent(proxy, "obfs-password", settings.text("obfs.password"));
            }
        } else {
            proxy.put("auth-str", node.credential());
            proxy.put("protocol", "udp");
            putIfPresent(proxy, "obfs", settings.flag("obfs.open")
                ? settings.text("obfs.password")
                : null);
        }
        return proxy;
    }

    private static Map<String, Object> mihomoHysteria(NodeClientView node) {
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

    static Map<String, Object> tuic(NodeClientView node, Dialect dialect) {
        return dialect.isStash() ? stashTuic(node) : mihomoTuic(node);
    }

    /**
     * Stash's tuic, which is mihomo's plus four timing knobs and minus
     * {@code udp}.
     *
     * The four are Stash's own defaults for its own QUIC stack, written on
     * every node rather than left to the client, and an admin reading the diff
     * against the mihomo output should not read them as this panel's choices.
     */
    private static Map<String, Object> stashTuic(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "tuic");
        if (settings.integer("version", 5) == 4) {
            proxy.put("token", node.credential());
        } else {
            proxy.put("uuid", node.credential());
            proxy.put("password", node.credential());
        }
        proxy.put("skip-cert-verify", settings.flag("tls.allow_insecure"));
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        proxy.put("congestion-controller", settings.text("congestion_control", "cubic"));
        proxy.put("udp-relay-mode", settings.text("udp_relay_mode", "native"));
        List<String> alpn = settings.strings("alpn");
        proxy.put("alpn", alpn.isEmpty() ? List.of("h3") : alpn);
        proxy.put("reduce-rtt", true);
        proxy.put("fast-open", true);
        proxy.put("heartbeat-interval", 10000);
        proxy.put("request-timeout", 8000);
        proxy.put("max-udp-relay-packet-size", 1500);
        proxy.put("version", settings.integer("version", 5));
        return proxy;
    }

    private static Map<String, Object> mihomoTuic(NodeClientView node) {
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

    static Map<String, Object> anytls(NodeClientView node, Dialect dialect) {
        SettingsView settings = node.settings();
        Map<String, Object> proxy = base(node, "anytls");
        proxy.put("password", node.credential());
        proxy.put("udp", true);
        putIfPresent(proxy, "sni", settings.text("tls.server_name"));
        if (dialect.isStash()) {
            // Written whether or not it is set, as Stash's builder does.
            proxy.put("skip-cert-verify", settings.flag("tls.allow_insecure"));
        } else if (settings.flag("tls.allow_insecure")) {
            proxy.put("skip-cert-verify", true);
        }
        if (dialect.isMihomo()) {
            TlsOptions.appendClashEch(proxy, settings, "tls.ech");
        }
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
     * @param defaultToTcp    whether an unsupported transport falls back to
     *                        tcp. The original's trojan builder does that in
     *                        its narrow form and not in the other two, and its
     *                        vmess builder never does, which is the sort of
     *                        difference a merge would quietly erase.
     * @param headerTransport whether this builder reads the tcp header type.
     *                        All three vmess builders do; of the trojan ones
     *                        only Stash's does, so a trojan node wearing an
     *                        http header goes direct to tcp in the other two.
     */
    private static void appendTransport(
        Map<String, Object> proxy,
        SettingsView settings,
        Dialect dialect,
        boolean defaultToTcp,
        boolean headerTransport
    ) {
        String network = settings.text("network", "tcp");
        switch (network) {
            case "tcp" -> proxy.put("network", "tcp");
            case "tcp-http" -> {
                if (!headerTransport) {
                    proxy.put("network", "tcp");
                    return;
                }
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
                if (!dialect.isWide()) {
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
                if (!dialect.isMihomo()) {
                    fallbackTransport(proxy, defaultToTcp);
                    return;
                }
                // mihomo has no httpupgrade transport; it is a websocket with
                // an upgrade header.
                proxy.put("network", "ws");
                Map<String, Object> options = new LinkedHashMap<>();
                options.put("v2ray-http-upgrade", true);
                putIfPresent(options, "path", settings.text("network_settings.path"));
                putIfPresent(options, "headers", hostHeader(settings));
                proxy.put("ws-opts", options);
            }
            case "xhttp" -> {
                if (!dialect.isMihomo()) {
                    fallbackTransport(proxy, defaultToTcp);
                    return;
                }
                proxy.put("network", "xhttp");
                Map<String, Object> options = new LinkedHashMap<>();
                putIfPresent(options, "path", settings.text("network_settings.path"));
                putIfPresent(options, "host", settings.text("network_settings.host"));
                putIfPresent(options, "mode", settings.text("network_settings.mode"));
                if (!options.isEmpty()) {
                    proxy.put("xhttp-opts", options);
                }
            }
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
     * The Host a transport should present.
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
