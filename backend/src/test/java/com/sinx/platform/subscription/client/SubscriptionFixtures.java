package com.sinx.platform.subscription.client;

import java.util.List;
import java.util.Map;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

import tools.jackson.databind.ObjectMapper;

/**
 * The node set the renderer tests are written against.
 *
 * One node per protocol, each with the settings its own format cares about, so
 * a renderer that quietly stops emitting a field shows up as a failing
 * assertion rather than as a config that is merely smaller than it used to be.
 */
final class SubscriptionFixtures {

    static final String IDENTITY = "11111111-2222-3333-4444-555555555555";
    static final String APP_NAME = "SinX Cloud";
    static final String APP_URL = "https://sinx.example";
    static final String REQUEST_HOST = "sub.example.com";

    private SubscriptionFixtures() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    static ClientConfigRequest request(SubscriptionTemplates.Kind kind, String template) {
        return new ClientConfigRequest(kind, template, APP_NAME, APP_URL, REQUEST_HOST);
    }

    static ClientConfigRequest genericRequest() {
        return new ClientConfigRequest(null, null, APP_NAME, APP_URL, REQUEST_HOST);
    }

    static NodeClientView node(
        String name,
        String protocol,
        String host,
        int port,
        String credential,
        Map<String, Object> settings
    ) {
        return new NodeClientView(
            name,
            List.of("hk"),
            protocol,
            host,
            port,
            credential,
            SettingsView.of(settings)
        );
    }

    static List<NodeClientView> nodes() {
        return List.of(
            shadowsocks(),
            shadowsocks2022(),
            vmess(),
            vless(),
            trojan(),
            hysteria(),
            tuic(),
            anytls(),
            socks(),
            http()
        );
    }

    static NodeClientView shadowsocks() {
        return node("香港 01", "shadowsocks", "hk1.example.com", 8443, IDENTITY, Map.of(
            "cipher", "aes-256-gcm",
            "plugin", "obfs",
            "plugin_opts", "obfs=http;obfs-host=www.bing.com"
        ));
    }

    static NodeClientView shadowsocks2022() {
        return node("SS2022", "shadowsocks", "hk2.example.com", 8444,
            "DJ8GmZ7f6kQ8hQvPQ2Z8rA:MTExMTExMTEtMjIyMi0zMw==", Map.of(
                "cipher", "2022-blake3-aes-128-gcm"
            ));
    }

    static NodeClientView vmess() {
        return node("vmess-ws", "vmess", "v.example.com", 443, IDENTITY, Map.of(
            "network", "ws",
            "tls", 1,
            "tls_settings", Map.of("server_name", "sni.example.com"),
            "network_settings", Map.of("path", "/ws", "host", "cdn.example.com")
        ));
    }

    static NodeClientView vless() {
        return node("vless-reality", "vless", "r.example.com", 443, IDENTITY, Map.of(
            "tls", 2,
            "reality_settings", Map.of(
                "public_key", "PBK", "short_id", "abcd", "server_name", "www.apple.com"
            ),
            "flow", "xtls-rprx-vision"
        ));
    }

    static NodeClientView trojan() {
        return node("trojan-grpc", "trojan", "t.example.com", 443, IDENTITY, Map.of(
            "network", "grpc",
            "tls", 1,
            "tls_settings", Map.of("server_name", "sni.example.com"),
            "network_settings", Map.of("service_name", "gsvc")
        ));
    }

    static NodeClientView hysteria() {
        return node("hy2", "hysteria", "h.example.com", 443, IDENTITY, Map.of(
            "version", 2,
            "tls", Map.of("server_name", "sni.example.com", "allow_insecure", false),
            "obfs", Map.of("open", true, "type", "salamander", "password", "obfspw"),
            "bandwidth", Map.of("up", 100, "down", 200)
        ));
    }

    /**
     * Hysteria 1, which is the version that takes bandwidth hints and the one
     * whose obfs the panel does not gate on {@code obfs.open}.
     */
    static NodeClientView hysteria1() {
        return node("hy1", "hysteria", "h1.example.com", 8443, IDENTITY, Map.of(
            "version", 1,
            "tls", Map.of("server_name", "sni.example.com", "allow_insecure", true),
            "obfs", Map.of("open", false, "password", "obfspw"),
            "bandwidth", Map.of("up", 100, "down", 200)
        ));
    }

    static NodeClientView tuic() {
        return node("tuic", "tuic", "u.example.com", 443, IDENTITY, Map.of(
            "tls", Map.of("server_name", "sni.example.com", "allow_insecure", false),
            "congestion_control", "bbr",
            "alpn", List.of("h3")
        ));
    }

    static NodeClientView anytls() {
        return node("anytls", "anytls", "a.example.com", 443, IDENTITY, Map.of(
            "tls", Map.of("server_name", "sni.example.com", "allow_insecure", true)
        ));
    }

    static NodeClientView socks() {
        return node("socks", "socks", "s.example.com", 1080, IDENTITY, Map.of());
    }

    static NodeClientView http() {
        return node("http", "http", "p.example.com", 8080, IDENTITY, Map.of());
    }
}
