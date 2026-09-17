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

    private static Map<String, Object> render(Dialect dialect, List<NodeClientView> nodes) {
        SubscriptionTemplates.Kind kind = switch (dialect) {
            case CLASH -> SubscriptionTemplates.Kind.CLASH;
            case META -> SubscriptionTemplates.Kind.CLASH_META;
            case STASH -> SubscriptionTemplates.Kind.STASH;
        };
        return ClashYaml.parse(
            new ClashRenderer(dialect)
                .render(
                    SubscriptionFixtures.request(kind, TEMPLATES.bundled(kind)),
                    nodes
                )
                .body()
        );
    }

    private static Map<String, Object> narrow() {
        return render(Dialect.CLASH, SubscriptionFixtures.nodes());
    }

    private static Map<String, Object> wide() {
        return render(Dialect.META, SubscriptionFixtures.nodes());
    }

    private static Map<String, Object> stash() {
        return render(Dialect.STASH, SubscriptionFixtures.nodes());
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

    /** Which of the fixture nodes this dialect wrote an entry for. */
    private static List<String> carried(Map<String, Object> config) {
        List<String> written = proxies(config).stream()
            .map(entry -> String.valueOf(entry.get("name")))
            .toList();
        return FIXTURE_NAMES.stream().filter(written::contains).toList();
    }

    private static final List<String> FIXTURE_NAMES = List.of(
        "香港 01", "SS2022", "vmess-ws", "vless-reality", "trojan-grpc",
        "hy2", "tuic", "anytls", "socks", "http"
    );

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
    // Stash
    // ------------------------------------------------------------------

    /**
     * Stash sits between the other two: it reads mihomo's whole protocol set
     * but none of its newer proxy fields. Mieru is the exception, and the one
     * protocol its own class leaves out of {@code allowedProtocols}.
     */
    @Test
    void stashCarriesTheWideProtocolSetMinusMieru() {
        assertThat(carried(stash()))
            .containsExactly(
                "香港 01", "SS2022", "vmess-ws", "vless-reality",
                "trojan-grpc", "hy2", "tuic", "anytls", "socks", "http"
            );

        NodeClientView mieru = SubscriptionFixtures.node(
            "mieru", "mieru", "m.example.com", 443, SubscriptionFixtures.IDENTITY, Map.of()
        );
        assertThat(proxy(render(Dialect.META, List.of(mieru)), "mieru"))
            .containsEntry("type", "mieru");
        assertThat(proxies(render(Dialect.STASH, List.of(mieru)))).isEmpty();
    }

    /**
     * Stash renames nearly everything mihomo's hysteria writes: the bandwidth
     * hints lose their hyphens, the credential key changes, and there is no
     * port-hopping at all, so no hop interval is written.
     */
    @Test
    void stashSpellsHysteriaItsOwnWay() {
        assertThat(proxy(stash(), "hy2")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
            Map.entry("name", "hy2"),
            Map.entry("type", "hysteria2"),
            Map.entry("server", "h.example.com"),
            Map.entry("port", 443),
            Map.entry("sni", "sni.example.com"),
            Map.entry("skip-cert-verify", false),
            Map.entry("auth", SubscriptionFixtures.IDENTITY),
            Map.entry("fast-open", true),
            Map.entry("obfs", "salamander"),
            Map.entry("obfs-password", "obfspw"),
            Map.entry("up-speed", 100),
            Map.entry("down-speed", 200)
        ));

        // Hysteria 1 keeps its own spelling of the credential, writes the udp
        // protocol on the line, and has no fast-open.
        assertThat(proxy(render(Dialect.STASH, List.of(SubscriptionFixtures.hysteria1())), "hy1"))
            .containsEntry("type", "hysteria")
            .containsEntry("auth-str", SubscriptionFixtures.IDENTITY)
            .containsEntry("protocol", "udp")
            .doesNotContainKey("fast-open")
            // The shared fixture leaves the obfs closed, and this builder reads
            // that flag.
            .doesNotContainKey("obfs");

        // With it open, hysteria 1's obfs holds the password alone - version 2
        // is the one that writes a type as well.
        NodeClientView openObfs = SubscriptionFixtures.node(
            "hy1-obfs", "hysteria", "h1.example.com", 8443, SubscriptionFixtures.IDENTITY,
            Map.of(
                "version", 1,
                "tls", Map.of("server_name", "sni.example.com", "allow_insecure", true),
                "obfs", Map.of("open", true, "password", "obfspw")
            )
        );
        assertThat(proxy(render(Dialect.STASH, List.of(openObfs)), "hy1-obfs"))
            .containsEntry("obfs", "obfspw")
            .doesNotContainKey("obfs-password");
    }

    /**
     * Stash's tuic carries four timing knobs of its own and no {@code udp} key,
     * which mihomo's has.
     */
    @Test
    void stashTuicCarriesItsOwnTimingKnobs() {
        assertThat(proxy(stash(), "tuic"))
            .containsEntry("reduce-rtt", true)
            .containsEntry("fast-open", true)
            .containsEntry("heartbeat-interval", 10000)
            .containsEntry("request-timeout", 8000)
            .containsEntry("max-udp-relay-packet-size", 1500)
            .containsEntry("version", 5)
            .containsEntry("alpn", List.of("h3"))
            .doesNotContainKey("udp");

        assertThat(proxy(wide(), "tuic")).containsEntry("udp", true);
    }

    /**
     * Stash derives the vmess cipher and alterId itself, so it writes neither,
     * and it has no multiplex or uTLS in vmess.
     */
    @Test
    void stashVmessWritesTheTlsKeysOnEveryNode() {
        assertThat(proxy(stash(), "vmess-ws")).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
            Map.entry("name", "vmess-ws"),
            Map.entry("type", "vmess"),
            Map.entry("server", "v.example.com"),
            Map.entry("port", 443),
            Map.entry("uuid", SubscriptionFixtures.IDENTITY),
            Map.entry("alterId", 0),
            Map.entry("cipher", "auto"),
            Map.entry("udp", true),
            Map.entry("tls", true),
            Map.entry("skip-cert-verify", false),
            Map.entry("servername", "sni.example.com"),
            Map.entry("network", "ws"),
            Map.entry("ws-opts", Map.of("path", "/ws", "headers", Map.of("Host", "cdn.example.com")))
        ));
    }

    /**
     * Stash's vless drops the three fields it derives, keeps the uTLS
     * fingerprint, and writes both {@code servername} and {@code sni} out of a
     * reality block where mihomo writes only the first.
     */
    @Test
    void stashVlessKeepsLessAndWritesSniForReality() {
        Map<String, Object> entry = proxy(stash(), "vless-reality");

        assertThat(entry)
            .containsEntry("tls", true)
            .containsEntry("servername", "www.apple.com")
            .containsEntry("sni", "www.apple.com")
            .containsEntry("flow", "xtls-rprx-vision")
            .containsEntry("reality-opts", Map.of("public-key", "PBK", "short-id", "abcd"))
            .doesNotContainKeys("alterId", "cipher", "encryption", "smux");
    }

    /**
     * Only Stash's socks and http write an {@code sni}; the other two builders
     * have never heard of one.
     */
    @Test
    void stashSocksAndHttpCarryAnSniTheOthersDoNot() {
        NodeClientView tlsSocks = SubscriptionFixtures.node(
            "socks-tls", "socks", "s.example.com", 1080, SubscriptionFixtures.IDENTITY,
            Map.of("tls", 1, "tls_settings", Map.of("server_name", "sni.example.com"))
        );

        assertThat(proxy(render(Dialect.STASH, List.of(tlsSocks)), "socks-tls"))
            .containsEntry("sni", "sni.example.com");
        assertThat(proxy(render(Dialect.META, List.of(tlsSocks)), "socks-tls"))
            .doesNotContainKey("sni");
    }

    /** The site address is offered to every member of the family, Stash included. */
    @Test
    void stashIsToldWhereTheSubscriptionCameFrom() {
        RenderedConfig rendered = new ClashRenderer(Dialect.STASH).render(
            SubscriptionFixtures.request(
                SubscriptionTemplates.Kind.STASH,
                TEMPLATES.bundled(SubscriptionTemplates.Kind.STASH)
            ),
            SubscriptionFixtures.nodes()
        );

        assertThat(rendered.headers())
            .containsEntry("profile-web-page-url", SubscriptionFixtures.APP_URL);
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
        Map<String, Object> config = render(Dialect.META, List.of());

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
        String body = new ClashRenderer(Dialect.META)
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
        RenderedConfig rendered = new ClashRenderer(Dialect.CLASH).render(
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
