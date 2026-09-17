package com.sinx.platform.subscription.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;

/**
 * sing-box, rendered from an administrator's template.
 *
 * The template supplies everything except the nodes: the inbounds, the DNS
 * block, the route rules and the outbound groups. Each node becomes one
 * outbound, tagged with the node's name, and the groups that ask for nodes are
 * filled in by matching their {@code include} and {@code exclude} patterns
 * against those tags.
 *
 * One key is not resolved here. The original also lets a group name a
 * {@code fallback} to use when its patterns match nothing; the key is removed
 * so it cannot reach a client that would reject it, but the fallback itself is
 * not looked up and a group that matches nothing stays empty.
 */
final class SingBoxRenderer implements ClientConfigRenderer {

    private final ObjectMapper mapper;

    SingBoxRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RenderedConfig render(ClientConfigRequest request, List<NodeClientView> nodes) {
        Map<String, Object> config = JsonMaps.parseObject(request.template(), mapper);
        if (config.isEmpty()) {
            throw new IllegalStateException(
                "The stored sing-box template is not a JSON object; it should have been "
                    + "rejected when it was saved."
            );
        }

        List<Object> proxies = new ArrayList<>();
        for (NodeClientView node : nodes) {
            Map<String, Object> outbound = outbound(node);
            if (outbound != null) {
                proxies.add(outbound);
            }
        }

        List<Object> outbounds = new ArrayList<>();
        if (config.get("outbounds") instanceof List<?> declared) {
            outbounds.addAll(declared);
        }
        expandGroups(outbounds, proxies);
        outbounds.addAll(proxies);
        config.put("outbounds", outbounds);

        String body = mapper.writeValueAsString(config);
        return new RenderedConfig(body, "application/json", Map.of(
            "profile-title", "base64:" + java.util.Base64.getEncoder()
                .encodeToString(request.appName().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "profile-update-interval", "24"
        ));
    }

    private Map<String, Object> outbound(NodeClientView node) {
        return switch (node.protocol()) {
            case "shadowsocks" -> shadowsocks(node);
            case "vmess" -> vmess(node);
            case "trojan" -> trojan(node);
            case "vless" -> supportsVlessTransport(node) ? vless(node) : null;
            case "hysteria" -> hysteria(node);
            case "tuic" -> tuic(node);
            case "anytls" -> anytls(node);
            case "socks" -> socks(node);
            case "http" -> http(node);
            default -> null;
        };
    }

    /**
     * sing-box has no h2 transport for vless, and the original drops the node
     * rather than rendering one it would refuse.
     */
    private static boolean supportsVlessTransport(NodeClientView node) {
        return switch (node.settings().text("network", "tcp")) {
            case "tcp", "tcp-http", "ws", "grpc", "httpupgrade" -> true;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // Outbounds
    // ------------------------------------------------------------------

    private static Map<String, Object> shadowsocks(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "shadowsocks");
        outbound.put("method", settings.text("cipher"));
        outbound.put("password", node.credential());
        if (settings.text("plugin") != null && settings.text("plugin_opts") != null) {
            outbound.put("plugin", settings.text("plugin"));
            outbound.put("plugin_opts", settings.text("plugin_opts"));
        }
        return outbound;
    }

    private static Map<String, Object> vmess(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "vmess");
        outbound.put("uuid", node.credential());
        outbound.put("security", "auto");
        outbound.put("alter_id", 0);

        if (settings.flag("tls")) {
            Map<String, Object> tls = enabledTls(settings.flag("tls_settings.allow_insecure"));
            appendUtls(tls, settings, node.name());
            TlsOptions.appendSingBoxEch(tls, settings, "tls_settings.ech");
            putIfPresent(tls, "server_name", settings.text("tls_settings.server_name"));
            outbound.put("tls", tls);
        }
        appendMultiplex(outbound, settings);
        putIfPresent(outbound, "transport", transport(settings, node.host()));
        return outbound;
    }

    private static Map<String, Object> vless(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "vless");
        outbound.put("uuid", node.credential());
        outbound.put("packet_encoding", "xudp");
        putIfPresent(outbound, "flow", settings.text("flow"));

        if (settings.flag("tls")) {
            int mode = settings.integer("tls", 0);
            boolean reality = mode == 2;
            Map<String, Object> tls = enabledTls(settings.flag(
                reality ? "reality_settings.allow_insecure" : "tls_settings.allow_insecure"
            ));
            appendUtls(tls, settings, node.name());
            if (reality) {
                tls.put("server_name", settings.text("reality_settings.server_name"));
                Map<String, Object> realityOptions = new LinkedHashMap<>();
                realityOptions.put("enabled", true);
                realityOptions.put("public_key", settings.text("reality_settings.public_key"));
                realityOptions.put("short_id", settings.text("reality_settings.short_id"));
                tls.put("reality", realityOptions);
            } else {
                TlsOptions.appendSingBoxEch(tls, settings, "tls_settings.ech");
                putIfPresent(tls, "server_name", settings.text("tls_settings.server_name"));
            }
            outbound.put("tls", tls);
        }
        appendMultiplex(outbound, settings);
        putIfPresent(outbound, "transport", transport(settings, node.host()));
        return outbound;
    }

    private static Map<String, Object> trojan(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "trojan");
        outbound.put("password", node.credential());

        Map<String, Object> tls = enabledTls(true);
        if (settings.integer("tls", 1) == 2) {
            tls.put("insecure", settings.flag("reality_settings.allow_insecure"));
            tls.put("server_name", settings.text("reality_settings.server_name"));
            Map<String, Object> realityOptions = new LinkedHashMap<>();
            realityOptions.put("enabled", true);
            realityOptions.put("public_key", settings.text("reality_settings.public_key"));
            realityOptions.put("short_id", settings.text("reality_settings.short_id"));
            tls.put("reality", realityOptions);
        } else {
            tls.put("insecure", settings.flag("tls_settings.allow_insecure"));
            TlsOptions.appendSingBoxEch(tls, settings, "tls_settings.ech");
            putIfPresent(tls, "server_name", settings.text("tls_settings.server_name"));
        }
        appendUtls(tls, settings, node.name());
        outbound.put("tls", tls);

        appendMultiplex(outbound, settings);
        putIfPresent(outbound, "transport", transport(settings, node.host()));
        return outbound;
    }

    private static Map<String, Object> hysteria(NodeClientView node) {
        SettingsView settings = node.settings();
        boolean version2 = settings.integer("version", 1) == 2;
        Map<String, Object> outbound = base(node, version2 ? "hysteria2" : "hysteria");

        Map<String, Object> tls = enabledTls(settings.flag("tls.allow_insecure"));
        putIfPresent(tls, "server_name", settings.text("tls.server_name"));
        TlsOptions.appendSingBoxEch(tls, settings, "tls.ech");
        outbound.put("tls", tls);

        putIfPresent(outbound, "up_mbps", settings.integer("bandwidth.up"));
        putIfPresent(outbound, "down_mbps", settings.integer("bandwidth.down"));

        if (version2) {
            outbound.put("password", node.credential());
            if (settings.flag("obfs.open")) {
                Map<String, Object> obfs = new LinkedHashMap<>();
                obfs.put("type", settings.text("obfs.type"));
                obfs.put("password", settings.text("obfs.password"));
                outbound.put("obfs", obfs);
            }
        } else {
            outbound.put("auth_str", node.credential());
            // Hysteria 1 is the one obfs the original does not gate on
            // obfs.open: it sends the password whenever one is set. Kept for
            // parity rather than corrected, since a node configured that way
            // is being served by the original today.
            putIfPresent(outbound, "obfs", settings.text("obfs.password"));
            outbound.put("disable_mtu_discovery", true);
        }
        return outbound;
    }

    private static Map<String, Object> tuic(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "tuic");
        outbound.put("congestion_control", settings.text("congestion_control", "cubic"));
        outbound.put("udp_relay_mode", settings.text("udp_relay_mode", "native"));
        outbound.put("zero_rtt_handshake", true);
        outbound.put("heartbeat", "10s");

        Map<String, Object> tls = enabledTls(settings.flag("tls.allow_insecure"));
        List<String> alpn = settings.strings("alpn");
        tls.put("alpn", alpn.isEmpty() ? List.of("h3") : alpn);
        putIfPresent(tls, "server_name", settings.text("tls.server_name"));
        TlsOptions.appendSingBoxEch(tls, settings, "tls.ech");
        outbound.put("tls", tls);

        if (settings.integer("version", 5) == 4) {
            outbound.put("token", node.credential());
        } else {
            outbound.put("uuid", node.credential());
            outbound.put("password", node.credential());
        }
        return outbound;
    }

    private static Map<String, Object> anytls(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "anytls");
        outbound.put("password", node.credential());

        Map<String, Object> tls = enabledTls(settings.flag("tls.allow_insecure"));
        List<String> alpn = settings.strings("alpn");
        tls.put("alpn", alpn.isEmpty() ? List.of("h3") : alpn);
        putIfPresent(tls, "server_name", settings.text("tls.server_name"));
        TlsOptions.appendSingBoxEch(tls, settings, "tls.ech");
        outbound.put("tls", tls);
        return outbound;
    }

    private static Map<String, Object> socks(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "socks");
        outbound.put("version", "5");
        outbound.put("username", node.credential());
        outbound.put("password", node.credential());
        if (settings.flag("udp_over_tcp")) {
            outbound.put("udp_over_tcp", true);
        }
        return outbound;
    }

    private static Map<String, Object> http(NodeClientView node) {
        SettingsView settings = node.settings();
        Map<String, Object> outbound = base(node, "http");
        outbound.put("username", node.credential());
        outbound.put("password", node.credential());
        putIfPresent(outbound, "path", settings.text("path"));
        Object headers = settings.raw("headers");
        if (headers instanceof Map<?, ?> map && !map.isEmpty()) {
            outbound.put("headers", map);
        }
        if (settings.flag("tls")) {
            Map<String, Object> tls = enabledTls(settings.flag("tls_settings.allow_insecure"));
            putIfPresent(tls, "server_name", settings.text("tls_settings.server_name"));
            TlsOptions.appendSingBoxEch(tls, settings, "tls_settings.ech");
            outbound.put("tls", tls);
        }
        return outbound;
    }

    // ------------------------------------------------------------------
    // Transports
    // ------------------------------------------------------------------

    /**
     * The transport stanza.
     *
     * This panel names the tcp-with-http-header transport {@code tcp-http} and
     * keeps its http settings under the same flat keys as everything else,
     * where the original nested them under a {@code header} object. The http
     * transport is therefore written with the original's defaults for a node
     * that set neither a path nor a Host.
     */
    private static Map<String, Object> transport(SettingsView settings, String nodeHost) {
        Map<String, Object> transport = new LinkedHashMap<>();
        switch (settings.text("network", "tcp")) {
            case "tcp" -> {
                return null;
            }
            case "tcp-http" -> {
                transport.put("type", "http");
                transport.put("path", "/");
                transport.put("host", List.of());
            }
            case "ws" -> {
                transport.put("type", "ws");
                putIfPresent(transport, "path", settings.text("network_settings.path"));
                String host = settings.text("network_settings.host");
                if (host != null) {
                    transport.put("headers", Map.of("Host", host));
                }
                transport.put("max_early_data", 0);
            }
            case "grpc" -> {
                transport.put("type", "grpc");
                putIfPresent(transport, "service_name", settings.text("network_settings.service_name"));
            }
            case "h2" -> {
                transport.put("type", "http");
                putIfPresent(transport, "host", settings.text("network_settings.host"));
                putIfPresent(transport, "path", settings.text("network_settings.path"));
            }
            case "httpupgrade" -> {
                transport.put("type", "httpupgrade");
                putIfPresent(transport, "path", settings.text("network_settings.path"));
                transport.put("host", settings.text("network_settings.host", nodeHost));
            }
            default -> {
                return null;
            }
        }
        return transport;
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    private static Map<String, Object> base(NodeClientView node, String type) {
        Map<String, Object> outbound = new LinkedHashMap<>();
        outbound.put("tag", node.name());
        outbound.put("type", type);
        outbound.put("server", node.host());
        outbound.put("server_port", node.port());
        return outbound;
    }

    private static Map<String, Object> enabledTls(boolean insecure) {
        Map<String, Object> tls = new LinkedHashMap<>();
        tls.put("enabled", true);
        tls.put("insecure", insecure);
        return tls;
    }

    private static void appendUtls(Map<String, Object> tls, SettingsView settings, String seed) {
        String fingerprint = TlsOptions.fingerprint(settings, seed);
        if (fingerprint == null) {
            return;
        }
        Map<String, Object> utls = new LinkedHashMap<>();
        utls.put("enabled", true);
        utls.put("fingerprint", fingerprint);
        tls.put("utls", utls);
    }

    private static void appendMultiplex(Map<String, Object> outbound, SettingsView settings) {
        if (!settings.flag("multiplex.enabled")) {
            return;
        }
        Map<String, Object> multiplex = new LinkedHashMap<>();
        multiplex.put("enabled", true);
        multiplex.put("protocol", settings.text("multiplex.protocol", "yamux"));
        putIfPresent(multiplex, "max_connections", settings.integer("multiplex.max_connections"));
        putIfPresent(multiplex, "min_streams", settings.integer("multiplex.min_streams"));
        putIfPresent(multiplex, "max_streams", settings.integer("multiplex.max_streams"));
        multiplex.put("padding", settings.flag("multiplex.padding"));
        if (settings.flag("multiplex.brutal.enabled")) {
            Map<String, Object> brutal = new LinkedHashMap<>();
            brutal.put("enabled", true);
            putIfPresent(brutal, "up_mbps", settings.integer("multiplex.brutal.up_mbps"));
            putIfPresent(brutal, "down_mbps", settings.integer("multiplex.brutal.down_mbps"));
            multiplex.put("brutal", brutal);
        }
        outbound.put("multiplex", multiplex);
    }

    /**
     * Fill in the outbound groups that name their members.
     *
     * {@code include} and {@code exclude} are read as patterns over the node
     * tags, and every node tag is appended to a group that names neither. A
     * group that asked for nodes and matched none stays as it was - empty -
     * rather than being dropped, because a client reads a missing group as a
     * broken config and an empty one as a group to fill in.
     */
    private static void expandGroups(List<Object> outbounds, List<Object> proxies) {
        List<String> allTags = new ArrayList<>();
        for (Object proxy : proxies) {
            if (proxy instanceof Map<?, ?> map && map.get("tag") instanceof String tag) {
                allTags.add(tag);
            }
        }
        for (Object entry : outbounds) {
            if (!(entry instanceof Map<?, ?> raw)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> group = (Map<String, Object>) raw;
            String type = String.valueOf(group.get("type"));
            if (!"urltest".equals(type) && !"selector".equals(type)) {
                continue;
            }
            String include = text(group.remove("include"));
            String exclude = text(group.remove("exclude"));
            // Removed whether or not it is resolved: sing-box would reject a
            // key it does not know.
            group.remove("fallback");

            List<String> tags = new ArrayList<>(allTags);
            if (include != null) {
                tags.removeIf(tag -> !SubscriptionNamePattern.matchesBare(include, tag));
            }
            if (exclude != null) {
                tags.removeIf(tag -> SubscriptionNamePattern.matchesBare(exclude, tag));
            }
            if (tags.isEmpty()) {
                continue;
            }
            List<Object> members = group.get("outbounds") instanceof List<?> declared
                ? new ArrayList<>(declared)
                : new ArrayList<>();
            members.addAll(tags);
            group.put("outbounds", members);
        }
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
