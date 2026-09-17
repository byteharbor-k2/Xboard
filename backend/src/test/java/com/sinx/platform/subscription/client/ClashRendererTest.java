package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * Fixed vectors for the Clash family, checked by parsing the YAML back rather
 * than by matching text: the output is a document a client parses, and the
 * whitespace is the dumper's business.
 *
 * What is pinned here is the shape of each proxy entry, the two rules that only
 * make sense against a node list (group expansion and the DIRECT rule for the
 * subscription's own address), and the divergence between the narrow and the
 * mihomo protocol sets.
 */
class ClashRendererTest {

    private static final SubscriptionTemplates TEMPLATES =
        new SubscriptionTemplates(SubscriptionFixtures.mapper());

    private static Map<String, Object> render(SubscriptionTemplates.Kind kind, boolean meta,
                                              List<NodeClientView> nodes) {
        return ClashYaml.parse(
            new ClashRenderer(meta)
                .render(
                    SubscriptionFixtures.request(kind, TEMPLATES.bundled(kind)),
                    nodes
                )
                .body()
        );
    }

    private static Map<String, Object> narrow() {
        return render(
            SubscriptionTemplates.Kind.CLASH, false, SubscriptionFixtures.nodes()
        );
    }

    private static Map<String, Object> wide() {
        return render(
            SubscriptionTemplates.Kind.CLASH_META, true, SubscriptionFixtures.nodes()
        );
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> proxies(Map<String, Object> config) {
        return (List<Map<String, Object>>) (List<?>) config.get("proxies");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> proxy(Map<String, Object> config, String name) {
        return proxies(config).stream()
            .filter(entry -> name.equals(entry.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No proxy named " + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> group(Map<String, Object> config, String name) {
        return ((List<Map<String, Object>>) (List<?>) config.get("proxy-groups")).stream()
            .filter(entry -> name.equals(entry.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No proxy group named " + name));
    }

    @SuppressWarnings("unchecked")
    private static List<String> groupNames(Map<String, Object> config) {
        return ((List<Map<String, Object>>) (List<?>) config.get("proxy-groups")).stream()
            .map(entry -> String.valueOf(entry.get("name")))
            .toList();
    }

    // ------------------------------------------------------------------
    // The narrow format
    // ------------------------------------------------------------------

    /**
     * The older clients this format targets cannot do shadowsocks 2022, vless,
     * hysteria, tuic or anytls, so those nodes are left out entirely rather
     * than written as proxies the client fails to start on.
     */
    @Test
    void theNarrowFormatRendersOnlyWhatItsClientsCanBuild() {
        assertThat(proxies(narrow()))
            .extracting(entry -> entry.get("name"))
            .containsExactly("香港 01", "vmess-ws", "trojan-grpc", "socks", "http");
    }

    @Test
    void theWideFormatRendersEveryNode() {
        assertThat(proxies(wide()))
            .extracting(entry -> entry.get("name"))
            .containsExactly(
                "香港 01", "SS2022", "vmess-ws", "vless-reality",
                "trojan-grpc", "hy2", "tuic", "anytls", "socks", "http"
            );
    }

    @Test
    void shadowsocksCarriesItsPluginOptionsInTheNarrowSpelling() {
        assertThat(proxy(narrow(), "香港 01")).containsExactlyInAnyOrderEntriesOf(Map.of(
            "name", "香港 01",
            "type", "ss",
            "server", "hk1.example.com",
            "port", 8443,
            "cipher", "aes-256-gcm",
            "password", SubscriptionFixtures.IDENTITY,
            "udp", true,
            "plugin", "obfs",
            "plugin-opts", Map.of("mode", "http", "host", "www.bing.com")
        ));
    }

    @Test
    void aShadowsocks2022NodeIsCarriedInTheWideFormatAlone() {
        assertThat(proxy(wide(), "SS2022")).containsEntry(
            "password", "DJ8GmZ7f6kQ8hQvPQ2Z8rA:MTExMTExMTEtMjIyMi0zMw=="
        );
        assertThat(proxy(wide(), "SS2022")).containsEntry(
            "cipher", "2022-blake3-aes-128-gcm"
        );
    }

    @Test
    void vmessWritesItsWebsocketTransport() {
        Map<String, Object> entry = proxy(narrow(), "vmess-ws");

        assertThat(entry).containsEntry("uuid", SubscriptionFixtures.IDENTITY);
        assertThat(entry).containsEntry("alterId", 0);
        assertThat(entry).containsEntry("cipher", "auto");
        assertThat(entry).containsEntry("udp", true);
        assertThat(entry).containsEntry("tls", true);
        assertThat(entry).containsEntry("skip-cert-verify", false);
        assertThat(entry).containsEntry("servername", "sni.example.com");
        assertThat(entry).containsEntry("network", "ws");
        assertThat(entry).containsEntry("ws-opts", Map.of(
            "path", "/ws",
            "headers", Map.of("Host", "cdn.example.com")
        ));
    }

    /**
     * The narrow trojan builder writes {@code tls: true} nowhere - it implies
     * TLS - while the wide one does. The difference is the original's.
     */
    @Test
    void trojanWritesItsGrpcTransportAndTheNarrowFormOmitsTls() {
        Map<String, Object> entry = proxy(narrow(), "trojan-grpc");

        assertThat(entry).doesNotContainKey("tls");
        assertThat(entry).containsEntry("password", SubscriptionFixtures.IDENTITY);
        assertThat(entry).containsEntry("sni", "sni.example.com");
        assertThat(entry).containsEntry("network", "grpc");
        assertThat(entry).containsEntry("grpc-opts", Map.of("grpc-service-name", "gsvc"));
    }

    @Test
    void socksAndHttpCarryTheCredentialTwice() {
        assertThat(proxy(narrow(), "socks"))
            .containsEntry("type", "socks5")
            .containsEntry("username", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .doesNotContainKey("tls");

        assertThat(proxy(narrow(), "http"))
            .containsEntry("type", "http")
            .containsEntry("username", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY);
    }

    // ------------------------------------------------------------------
    // The mihomo-only protocols
    // ------------------------------------------------------------------

    @Test
    void vlessRealityWritesItsRealityOptions() {
        Map<String, Object> entry = proxy(wide(), "vless-reality");

        assertThat(entry).containsEntry("tls", true);
        assertThat(entry).containsEntry("skip-cert-verify", false);
        assertThat(entry).containsEntry("servername", "www.apple.com");
        assertThat(entry).containsEntry("flow", "xtls-rprx-vision");
        assertThat(entry).containsEntry("encryption", "none");
        assertThat(entry).containsEntry("network", "tcp");
        assertThat(entry).containsEntry("reality-opts", Map.of(
            "public-key", "PBK",
            "short-id", "abcd"
        ));
    }

    @Test
    void hysteria2WritesItsObfsAndBandwidth() {
        assertThat(proxy(wide(), "hy2")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
            Map.entry("name", "hy2"),
            Map.entry("type", "hysteria2"),
            Map.entry("server", "h.example.com"),
            Map.entry("port", 443),
            Map.entry("sni", "sni.example.com"),
            Map.entry("skip-cert-verify", false),
            Map.entry("password", SubscriptionFixtures.IDENTITY),
            Map.entry("obfs", "salamander"),
            Map.entry("obfs-password", "obfspw"),
            Map.entry("up", 100),
            Map.entry("down", 200)
        ));
    }

    @Test
    void tuicAndAnytlsWriteTheirOwnSpelling() {
        assertThat(proxy(wide(), "tuic"))
            .containsEntry("type", "tuic")
            .containsEntry("uuid", SubscriptionFixtures.IDENTITY)
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .containsEntry("alpn", List.of("h3"))
            .containsEntry("congestion-controller", "bbr")
            .containsEntry("udp-relay-mode", "native");

        assertThat(proxy(wide(), "anytls"))
            .containsEntry("type", "anytls")
            .containsEntry("password", SubscriptionFixtures.IDENTITY)
            .containsEntry("sni", "sni.example.com")
            .containsEntry("skip-cert-verify", true);
    }

    // ------------------------------------------------------------------
    // Proxy groups
    // ------------------------------------------------------------------

    @Test
    void aGroupOfPlainNamesGetsEveryNodeAppended() {
        Map<String, Object> group = group(narrow(), "SinX Cloud");

        assertThat(group.get("proxies")).asList().startsWith(
            "自动选择", "故障转移", "DIRECT"
        ).hasSize(8);
    }

    @Test
    void aRegionGroupIsFilledFromItsPattern() {
        assertThat(group(wide(), "🇭🇰 香港").get("proxies"))
            .asList()
            .containsExactly("香港 01");
    }

    @Test
    void aRegionGroupThatMatchesNothingIsDropped() {
        assertThat(groupNames(wide()))
            .containsExactly("SinX Cloud", "🚀 自动选择", "♻️ 故障转移", "🇭🇰 香港");
    }

    /**
     * With no nodes the original never sets its {@code isFilter} flag, so no
     * group is expanded: the region groups keep their literal patterns, and the
     * two automatic groups that asked for nodes end up empty and are dropped
     * rather than being served as entries nobody can select.
     */
    @Test
    void withNoNodesThePatternsAreLeftAsWritten() {
        Map<String, Object> config = render(
            SubscriptionTemplates.Kind.CLASH_META, true, List.of()
        );

        assertThat(groupNames(config)).containsExactly(
            "SinX Cloud", "🇭🇰 香港", "🇹🇼 台湾", "🇯🇵 日本", "🇸🇬 新加坡", "🇺🇸 美国"
        );
        assertThat(group(config, "🇭🇰 香港").get("proxies"))
            .asList()
            .containsExactly("/香港|HK|HongKong|Hong Kong/i");
        assertThat(group(config, "SinX Cloud").get("proxies"))
            .asList()
            .containsExactly("🚀 自动选择", "♻️ 故障转移", "DIRECT");
    }

    // ------------------------------------------------------------------
    // The rule, the name, and the headers
    // ------------------------------------------------------------------

    @Test
    void theSubscriptionAddressIsRoutedDirect() {
        assertThat(narrow().get("rules")).asList()
            .first()
            .isEqualTo("DOMAIN,sub.example.com,DIRECT");
    }

    @Test
    void theSiteNameIsSubstitutedWhereverTheTemplateWroteIt() {
        String body = new ClashRenderer(true)
            .render(
                SubscriptionFixtures.request(
                    SubscriptionTemplates.Kind.CLASH,
                    TEMPLATES.bundled(SubscriptionTemplates.Kind.CLASH)
                ),
                SubscriptionFixtures.nodes()
            )
            .body();

        assertThat(body).doesNotContain("$app_name");
        assertThat(body).contains("name: SinX Cloud");
        assertThat(body).contains("MATCH,SinX Cloud");
    }

    @Test
    void theHeadersTellTheClientWhatItFetched() {
        RenderedConfig rendered = new ClashRenderer(false).render(
            SubscriptionFixtures.request(
                SubscriptionTemplates.Kind.CLASH,
                TEMPLATES.bundled(SubscriptionTemplates.Kind.CLASH)
            ),
            SubscriptionFixtures.nodes()
        );

        assertThat(rendered.contentType()).isEqualTo("text/yaml");
        assertThat(rendered.headers()).isEqualTo(Map.of(
            "profile-update-interval", "24",
            "content-disposition", "attachment;filename*=UTF-8''SinX%20Cloud",
            "profile-web-page-url", SubscriptionFixtures.APP_URL
        ));
    }
}
