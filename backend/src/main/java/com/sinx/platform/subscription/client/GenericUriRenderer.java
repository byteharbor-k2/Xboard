package com.sinx.platform.subscription.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * The generic v2ray link list.
 *
 * Unlike the other two this is not template-driven, because it never was: the
 * original builds it in code and hands the whole body back base64-encoded.
 * What that body is worth is that any client can read it - v2rayN, v2rayNG,
 * Shadowrocket, Passwall - which is also why this is the fallback when nothing
 * else matches.
 *
 * The links are what the original's {@code General} protocol emits, byte for
 * byte, quirks included: the vmess block is a JSON object with several keys
 * that are almost always null, the vless fragment is percent-encoded one way
 * while every other fragment is encoded another, and each link ends with the
 * {@code CRLF} that no client needs.
 */
final class GenericUriRenderer implements ClientConfigRenderer {

    private final ObjectMapper mapper;

    GenericUriRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RenderedConfig render(ClientConfigRequest request, List<NodeClientView> nodes) {
        StringBuilder body = new StringBuilder();
        for (NodeClientView node : nodes) {
            String link = link(node);
            if (link != null) {
                body.append(link);
            }
        }
        String encoded = Base64.getEncoder()
            .encodeToString(body.toString().getBytes(StandardCharsets.UTF_8));
        return new RenderedConfig(encoded, "text/plain", Map.of());
    }

    private String link(NodeClientView node) {
        return switch (node.protocol()) {
            case "shadowsocks" -> shadowsocks(node);
            case "vmess" -> vmess(node);
            case "vless" -> vless(node);
            case "trojan" -> trojan(node);
            case "hysteria" -> hysteria(node);
            case "tuic" -> tuic(node);
            case "anytls" -> anytls(node);
            case "socks" -> socks(node);
            case "http" -> http(node);
            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // The links
    // ------------------------------------------------------------------

    /**
     * Shadowsocks hands the client its cipher and password joined and
     * base64-encoded, in the URL-safe alphabet with the padding dropped - the
     * older SIP002 form, not the one that puts the two on the query string.
     */
    private static String shadowsocks(NodeClientView node) {
        SettingsView settings = node.settings();
        String credential = Base64.getUrlEncoder().withoutPadding().encodeToString(
            (settings.text("cipher", "") + ":" + node.credential())
                .getBytes(StandardCharsets.UTF_8)
        );
        StringBuilder link = new StringBuilder()
            .append("ss://").append(credential)
            .append('@').append(V2rayUriEncoding.host(node.host()))
            .append(':').append(node.port());
        String plugin = settings.text("plugin");
        String pluginOpts = settings.text("plugin_opts");
        if (plugin != null && pluginOpts != null) {
            link.append("/?plugin=")
                .append(V2rayUriEncoding.rawUrlEncode(plugin + ";" + pluginOpts));
        }
        return link.append('#').append(V2rayUriEncoding.fragment(node.name()))
            .append("\r\n").toString();
    }

    /**
     * vmess is a base64-encoded JSON object rather than a URL.
     *
     * The shape is fixed by the original: a handful of keys exist whether or not
     * they mean anything, {@code net} keeps saying tcp for websockets and gRPC,
     * and only the tcp and h2 families rewrite it.
     */
    private String vmess(NodeClientView node) {
        SettingsView settings = node.settings();
        String network = settings.text("network", "tcp");
        boolean httpHeader = "tcp-http".equals(network);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("v", "2");
        config.put("ps", node.name());
        config.put("add", node.host());
        config.put("port", String.valueOf(node.port()));
        config.put("id", node.credential());
        config.put("aid", "0");
        config.put("net", "tcp-http".equals(network) ? "tcp" : network);
        config.put("type", "none");
        config.put("host", "");
        config.put("path", "");
        config.put("tls", settings.flag("tls") ? "tls" : "");

        putIfPresent(config, "sni", settings.text("tls_settings.server_name"));
        putIfPresent(config, "fp", TlsOptions.fingerprint(settings, node.name()));

        switch (network) {
            case "tcp" -> {
            }
            case "tcp-http" -> {
                // The original reads a path and a Host header off the node; this
                // panel stores neither, so the defaults it would have used stand.
                config.put("type", "http");
                config.put("path", "/");
                config.put("host", null);
            }
            case "ws" -> {
                config.put("type", "ws");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "grpc" -> {
                config.put("type", "grpc");
                putIfPresent(config, "path", settings.text("network_settings.service_name"));
            }
            case "h2" -> {
                config.put("net", "h2");
                config.put("type", "h2");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "httpupgrade" -> {
                config.put("net", "httpupgrade");
                config.put("type", "httpupgrade");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", node.host()));
            }
            case "xhttp" -> {
                config.put("net", "xhttp");
                config.put("type", "xhttp");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", node.host()));
                config.put("mode", settings.text("network_settings.mode", "auto"));
            }
            default -> {
            }
        }
        String json = mapper.writeValueAsString(config);
        return "vmess://"
            + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))
            + "\r\n";
    }

    private static String vless(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("mode", "multi");
        config.put("security", "");
        config.put("encryption", settings.flag("encryption.enabled")
            ? settings.text("encryption.encryption", "none")
            : "none");
        config.put("type", settings.text("network", "tcp"));
        putIfPresent(config, "flow", settings.text("flow"));

        switch (settings.integer("tls", 0)) {
            case 1 -> {
                config.put("security", "tls");
                putIfPresent(config, "fp", TlsOptions.fingerprint(settings, node.name()));
                putIfPresent(config, "sni", settings.text("tls_settings.server_name"));
                if (settings.flag("tls_settings.allow_insecure")) {
                    config.put("allowInsecure", "1");
                }
            }
            case 2 -> {
                config.put("security", "reality");
                config.put("pbk", settings.text("reality_settings.public_key"));
                config.put("sid", settings.text("reality_settings.short_id"));
                config.put("sni", settings.text("reality_settings.server_name"));
                config.put("servername", settings.text("reality_settings.server_name"));
                config.put("spx", "/");
                putIfPresent(config, "fp", TlsOptions.fingerprint(settings, node.name()));
            }
            default -> {
            }
        }

        appendVlessTransport(config, settings, node.host());

        String authority = node.credential()
            + '@' + V2rayUriEncoding.host(node.host())
            + ':' + node.port();
        return "vless://" + authority
            + '?' + V2rayUriEncoding.query(config)
            + '#' + V2rayUriEncoding.formEncode(node.name())
            + "\r\n";
    }

    /**
     * The vless transport query.
     *
     * Unlike vmess this is a URL, so the transport is expressed as extra query
     * parameters rather than as {@code net} and {@code type} fields, and h2
     * becomes {@code type=http}.
     */
    private static void appendVlessTransport(
        Map<String, Object> config,
        SettingsView settings,
        String nodeHost
    ) {
        switch (settings.text("network", "tcp")) {
            case "tcp" -> {
            }
            case "tcp-http" -> config.put("type", "http");
            case "ws" -> {
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "grpc" -> putIfPresent(
                config, "serviceName", settings.text("network_settings.service_name")
            );
            case "h2" -> {
                config.put("type", "http");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "httpupgrade" -> {
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", nodeHost));
            }
            case "xhttp" -> {
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", nodeHost));
                config.put("mode", settings.text("network_settings.mode", "auto"));
            }
            default -> {
            }
        }
    }

    /**
     * Trojan's TLS block, in the shape v2rayN reads: reality is described with
     * the same keys as vless, and plain TLS carries an explicit
     * {@code allowInsecure} that is written even when false.
     */
    private static String trojan(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> config = new LinkedHashMap<>();
        if (settings.integer("tls", 1) == 2) {
            config.put("security", "reality");
            config.put("pbk", settings.text("reality_settings.public_key"));
            config.put("sid", settings.text("reality_settings.short_id"));
            config.put("sni", settings.text("reality_settings.server_name"));
            putIfPresent(config, "fp", TlsOptions.fingerprint(settings, node.name()));
        } else {
            config.put("allowInsecure", settings.flag("tls_settings.allow_insecure"));
            String serverName = settings.text("tls_settings.server_name");
            if (serverName != null) {
                config.put("peer", serverName);
                config.put("sni", serverName);
            }
            putIfPresent(config, "fp", TlsOptions.fingerprint(settings, node.name()));
        }

        switch (settings.text("network", "tcp")) {
            case "ws" -> {
                config.put("type", "ws");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "grpc" -> {
                config.put("type", "grpc");
                putIfPresent(config, "serviceName", settings.text("network_settings.service_name"));
            }
            case "h2" -> {
                config.put("type", "http");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                putIfPresent(config, "host", settings.text("network_settings.host"));
            }
            case "httpupgrade" -> {
                config.put("type", "httpupgrade");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", node.host()));
            }
            case "xhttp" -> {
                config.put("type", "xhttp");
                putIfPresent(config, "path", settings.text("network_settings.path"));
                config.put("host", settings.text("network_settings.host", node.host()));
                config.put("mode", settings.text("network_settings.mode", "auto"));
            }
            default -> {
            }
        }

        return "trojan://" + node.credential()
            + '@' + V2rayUriEncoding.host(node.host())
            + ':' + node.port()
            + '?' + V2rayUriEncoding.query(config)
            + '#' + V2rayUriEncoding.fragment(node.name())
            + "\r\n";
    }

    /**
     * Hysteria has two versions and two link shapes.
     *
     * Version 1 puts the password in an {@code auth} parameter and keeps the
     * authority free of it; version 2 puts it back in the authority and drops
     * the bandwidth parameters, which it no longer takes.
     */
    private static String hysteria(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> parameters = new LinkedHashMap<>();
        putIfPresent(parameters, "sni", settings.text("tls.server_name"));
        parameters.put("insecure", settings.flag("tls.allow_insecure") ? "1" : "0");

        String authority = V2rayUriEncoding.host(node.host()) + ":" + node.port();
        String fragment = V2rayUriEncoding.fragment(node.name());

        if (settings.integer("version", 2) == 2) {
            if (settings.flag("obfs.open")) {
                parameters.put("obfs", "salamander");
                putIfPresent(parameters, "obfs-password", settings.text("obfs.password"));
            }
            return "hysteria2://" + node.credential() + '@' + authority
                + '?' + V2rayUriEncoding.query(parameters)
                + '#' + fragment + "\r\n";
        }

        parameters.put("protocol", "udp");
        parameters.put("auth", node.credential());
        putBandwidth(parameters, "upmbps", settings, "bandwidth.up");
        putBandwidth(parameters, "downmbps", settings, "bandwidth.down");
        if (
            settings.flag("obfs.open")
                && settings.text("obfs.password") != null
        ) {
            parameters.put("obfs", "xplus");
            parameters.put("obfsParam", settings.text("obfs.password"));
        }
        return "hysteria://" + authority
            + '?' + V2rayUriEncoding.query(parameters)
            + '#' + fragment + "\r\n";
    }

    /**
     * A hysteria 1 bandwidth hint, when the node has one.
     *
     * The setting is a number, and the original omits it when it is zero rather
     * than writing {@code upmbps=0} - which a client would take literally and
     * cap itself at nothing.
     */
    private static void putBandwidth(
        Map<String, Object> parameters,
        String key,
        SettingsView settings,
        String path
    ) {
        Integer value = settings.integer(path);
        if (value != null && value != 0) {
            parameters.put(key, value);
        }
    }

    private static String tuic(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> parameters = new LinkedHashMap<>();
        putIfPresent(parameters, "sni", settings.text("tls.server_name"));
        List<String> alpn = settings.strings("alpn");
        if (!alpn.isEmpty()) {
            parameters.put("alpn", String.join(",", alpn));
        }
        parameters.put("congestion_control", settings.text("congestion_control", "cubic"));
        parameters.put("udp-relay-mode", settings.text("udp_relay_mode", "native"));
        if (settings.flag("tls.allow_insecure")) {
            parameters.put("insecure", "1");
        }

        return "tuic://" + node.credential() + ':' + node.credential()
            + '@' + V2rayUriEncoding.host(node.host())
            + ':' + node.port()
            + '?' + V2rayUriEncoding.query(parameters)
            + '#' + V2rayUriEncoding.fragment(node.name())
            + "\r\n";
    }

    private static String anytls(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> parameters = new LinkedHashMap<>();
        putIfPresent(parameters, "sni", settings.text("tls.server_name"));
        parameters.put("insecure", settings.raw("tls.allow_insecure"));
        return "anytls://" + node.credential()
            + '@' + V2rayUriEncoding.host(node.host())
            + ':' + node.port()
            + '?' + V2rayUriEncoding.query(parameters)
            + '#' + V2rayUriEncoding.fragment(node.name())
            + "\r\n";
    }

    /** SOCKS and HTTP carry their single credential as a joined basic-auth pair. */
    private static String socks(NodeClientView node) {
        return "socks://" + joinedCredential(node)
            + '@' + V2rayUriEncoding.host(node.host())
            + ':' + node.port()
            + '#' + V2rayUriEncoding.fragment(node.name())
            + "\r\n";
    }

    private static String http(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (settings.flag("tls")) {
            parameters.put("security", "tls");
            putIfPresent(parameters, "sni", settings.text("tls_settings.server_name"));
            parameters.put(
                "allowInsecure",
                settings.flag("tls_settings.allow_insecure") ? "1" : "0"
            );
        }
        StringBuilder link = new StringBuilder()
            .append("http://").append(joinedCredential(node))
            .append('@').append(V2rayUriEncoding.host(node.host()))
            .append(':').append(node.port());
        if (!parameters.isEmpty()) {
            link.append('?').append(V2rayUriEncoding.query(parameters));
        }
        return link.append('#').append(V2rayUriEncoding.fragment(node.name()))
            .append("\r\n").toString();
    }

    private static String joinedCredential(NodeClientView node) {
        return Base64.getEncoder().encodeToString(
            (node.credential() + ":" + node.credential()).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
