package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * Fixed vectors for sing-box: one outbound per node of the right type with the
 * transport and TLS block its protocol needs, and the group expansion that
 * fills the template's selectors in.
 */
class SingBoxRendererTest {

    private static final SubscriptionTemplates TEMPLATES =
        new SubscriptionTemplates(SubscriptionFixtures.mapper());
    private static final tools.jackson.databind.ObjectMapper JSON =
        SubscriptionFixtures.mapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> render(String template, List<NodeClientView> nodes) {
        try {
            return JSON.readValue(
                new SingBoxRenderer(JSON)
                    .render(
                        SubscriptionFixtures.request(
                            SubscriptionTemplates.Kind.SING_BOX, template
                        ),
                        nodes
                    )
                    .body(),
                Map.class
            );
        } catch (Exception exception) {
            throw new AssertionError("The renderer produced unreadable JSON", exception);
        }
    }

    private static Map<String, Object> bundled() {
        return render(
            TEMPLATES.bundled(SubscriptionTemplates.Kind.SING_BOX),
            SubscriptionFixtures.nodes()
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> outbounds(Map<String, Object> config) {
        return (List<Map<String, Object>>) (List<?>) config.get("outbounds");
    }

    private static Map<String, Object> outbound(Map<String, Object> config, String tag) {
        return outbounds(config).stream()
            .filter(entry -> tag.equals(entry.get("tag")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No outbound tagged " + tag));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> entry, String key) {
        return (Map<String, Object>) entry.get(key);
    }

    // ------------------------------------------------------------------
    // One outbound per protocol
    // ------------------------------------------------------------------

    @Test
    void everyNodeBecomesAnOutboundOfItsOwnType() {
        assertThat(bundled().get("outbounds")).asList()
            .extracting(entry -> String.valueOf(((Map<?, ?>) entry).get("tag")))
            .containsExactly(
                "节点选择", "direct", "自动选择",
                "香港 01", "SS2022", "vmess-ws", "vless-reality",
                "trojan-grpc", "hy2", "tuic", "anytls", "socks", "http"
            );
    }

    @Test
    void shadowsocksKeepsItsPluginAsTwoRawStrings() {
        assertThat(outbound(bundled(), "香港 01")).containsEntry("type", "shadowsocks")
            .containsEntry("method", "aes-256-gcm")
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .containsEntry("plugin", "obfs")
            .containsEntry("plugin_opts", "obfs=http;obfs-host=www.bing.com");
    }

    @Test
    void aShadowsocks2022NodeCarriesTheJoinedPassword() {
        assertThat(outbound(bundled(), "SS2022"))
            .containsEntry("method", "2022-blake3-aes-128-gcm")
            .containsEntry("password", "DJ8GmZ7f6kQ8hQvPQ2Z8rA:MTExMTExMTEtMjIyMi0zMw==");
    }

    @Test
    void vmessWritesItsWebsocketTransportAndTls() {
        Map<String, Object> entry = outbound(bundled(), "vmess-ws");

        assertThat(entry).containsEntry("uuid", SubscriptionFixtures.IDENTITY)
            .containsEntry("security", "auto")
            .containsEntry("alter_id", 0);
        assertThat(nested(entry, "tls")).containsEntry("enabled", true)
            .containsEntry("insecure", false)
            .containsEntry("server_name", "sni.example.com");
        assertThat(nested(entry, "transport")).containsEntry("type", "ws")
            .containsEntry("path", "/ws")
            .containsEntry("max_early_data", 0)
            .containsEntry("headers", Map.of("Host", "cdn.example.com"));
    }

    @Test
    void vlessRealityWritesItsRealityBlock() {
        Map<String, Object> entry = outbound(bundled(), "vless-reality");

        assertThat(entry).containsEntry("uuid", SubscriptionFixtures.IDENTITY)
            .containsEntry("packet_encoding", "xudp")
            .containsEntry("flow", "xtls-rprx-vision")
            .doesNotContainKey("transport");
        assertThat(nested(entry, "tls")).containsEntry("server_name", "www.apple.com");
        assertThat(nested(nested(entry, "tls"), "reality"))
            .containsEntry("enabled", true)
            .containsEntry("public_key", "PBK")
            .containsEntry("short_id", "abcd");
    }

    @Test
    void trojanWritesItsGrpcServiceName() {
        Map<String, Object> entry = outbound(bundled(), "trojan-grpc");

        assertThat(entry).containsEntry("password", SubscriptionFixtures.IDENTITY);
        assertThat(nested(entry, "transport"))
            .containsEntry("type", "grpc")
            .containsEntry("service_name", "gsvc");
        assertThat(nested(entry, "tls")).containsEntry("insecure", false)
            .containsEntry("server_name", "sni.example.com");
    }

    @Test
    void hysteria2WritesItsObfsAndBandwidth() {
        Map<String, Object> entry = outbound(bundled(), "hy2");

        assertThat(entry).containsEntry("type", "hysteria2")
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .containsEntry("up_mbps", 100)
            .containsEntry("down_mbps", 200);
        assertThat(nested(entry, "obfs"))
            .containsEntry("type", "salamander")
            .containsEntry("password", "obfspw");
    }

    /**
     * Hysteria 1 puts the password in {@code auth_str} rather than
     * {@code password}, and its obfs is the one the panel does not gate on
     * {@code obfs.open} - the original sends the password whenever one is set.
     */
    @Test
    void hysteria1SendsItsObfsWhetherOrNotTheNodeOpenedIt() {
        Map<String, Object> entry = outbound(
            render(TEMPLATES.bundled(SubscriptionTemplates.Kind.SING_BOX),
                List.of(SubscriptionFixtures.hysteria1())),
            "hy1"
        );

        assertThat(entry).containsEntry("type", "hysteria")
            .containsEntry("auth_str", SubscriptionFixtures.IDENTITY)
            .containsEntry("obfs", "obfspw")
            .containsEntry("disable_mtu_discovery", true)
            .doesNotContainKey("password");
    }

    @Test
    void tuicAndAnytlsDefaultTheirAlpn() {
        Map<String, Object> tuic = outbound(bundled(), "tuic");

        assertThat(tuic).containsEntry("type", "tuic")
            .containsEntry("uuid", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .containsEntry("congestion_control", "bbr")
            .containsEntry("udp_relay_mode", "native")
            .containsEntry("zero_rtt_handshake", true)
            .containsEntry("heartbeat", "10s");
        assertThat(nested(tuic, "tls")).containsEntry("alpn", List.of("h3"));

        Map<String, Object> anytls = outbound(bundled(), "anytls");
        assertThat(anytls).containsEntry("password", SubscriptionFixtures.IDENTITY);
        assertThat(nested(anytls, "tls")).containsEntry("alpn", List.of("h3"))
            .containsEntry("insecure", true);
    }

    @Test
    void socksAndHttpCarryTheCredentialTwice() {
        assertThat(outbound(bundled(), "socks"))
            .containsEntry("type", "socks")
            .containsEntry("version", "5")
            .containsEntry("username", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY);

        assertThat(outbound(bundled(), "http"))
            .containsEntry("type", "http")
            .containsEntry("username", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY);
    }

    /** sing-box has no h2 transport for vless, so the node is dropped. */
    @Test
    void aVlessNodeOnAnUnsupportedTransportIsLeftOut() {
        NodeClientView unsupported = SubscriptionFixtures.node(
            "vless-h2", "vless", "r2.example.com", 443, SubscriptionFixtures.IDENTITY,
            Map.of("network", "h2", "tls", 1, "network_settings", Map.of("path", "/"))
        );
        NodeClientView plain = SubscriptionFixtures.node(
            "vless-tcp", "vless", "r3.example.com", 443, SubscriptionFixtures.IDENTITY,
            Map.of("network", "tcp")
        );

        Map<String, Object> config = render(
            TEMPLATES.bundled(SubscriptionTemplates.Kind.SING_BOX),
            List.of(unsupported, plain)
        );

        assertThat(outbounds(config))
            .extracting(entry -> String.valueOf(entry.get("tag")))
            .contains("vless-tcp")
            .doesNotContain("vless-h2");
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    @Test
    void aGroupThatNamesNoPatternGetsEveryNode() {
        assertThat(outbound(bundled(), "自动选择").get("outbounds")).asList()
            .hasSize(10)
            .first()
            .isEqualTo("香港 01");
        assertThat(outbound(bundled(), "节点选择").get("outbounds")).asList()
            .startsWith("自动选择")
            .hasSize(11);
    }

    @Test
    void anOutboundThatIsNotAGroupIsLeftAlone() {
        assertThat(outbound(bundled(), "direct")).doesNotContainKey("outbounds");
    }

    @Test
    void includeAndExcludeFilterTheNodesTheGroupNames() {
        String template = """
            {"outbounds":[
              {"tag":"hk","type":"selector","include":"香港"},
              {"tag":"nohk","type":"urltest","exclude":"/香港|HK/i"},
              {"tag":"nowhere","type":"selector","include":"火星"},
              {"tag":"direct","type":"direct"}
            ]}
            """;

        Map<String, Object> config = render(template, SubscriptionFixtures.nodes());

        assertThat(outbound(config, "hk").get("outbounds")).asList()
            .containsExactly("香港 01");
        assertThat(outbound(config, "nohk").get("outbounds")).asList()
            .hasSize(9)
            .doesNotContain("香港 01");
        assertThat(outbound(config, "nowhere")).doesNotContainKey("outbounds");
        assertThat(outbound(config, "hk")).doesNotContainKey("include");
        assertThat(outbound(config, "nohk")).doesNotContainKey("exclude");
    }

    /**
     * sing-box rejects a key it does not know, so {@code fallback} comes out of
     * the output whether or not the group's patterns matched anything.
     */
    @Test
    void aFallbackKeyIsRemovedEvenWhenItIsNotResolved() {
        String template = """
            {"outbounds":[
              {"tag":"hk","type":"selector","include":"香港","fallback":"direct"}
            ]}
            """;

        Map<String, Object> config = render(template, SubscriptionFixtures.nodes());

        assertThat(outbound(config, "hk")).doesNotContainKey("fallback")
            .containsEntry("outbounds", List.of("香港 01"));
    }

    @Test
    void theHeadersCarryTheEncodedSiteName() {
        RenderedConfig rendered = new SingBoxRenderer(JSON).render(
            SubscriptionFixtures.request(
                SubscriptionTemplates.Kind.SING_BOX,
                TEMPLATES.bundled(SubscriptionTemplates.Kind.SING_BOX)
            ),
            SubscriptionFixtures.nodes()
        );

        assertThat(rendered.contentType()).isEqualTo("application/json");
        assertThat(rendered.headers()).isEqualTo(Map.of(
            "profile-title", "base64:U2luWCBDbG91ZA==",
            "profile-update-interval", "24"
        ));
    }
}
