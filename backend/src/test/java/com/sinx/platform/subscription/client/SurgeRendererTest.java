package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * Fixed vectors for the two Surge-grammar formats.
 *
 * These templates are text rather than a document, so unlike the Clash tests
 * there is nothing to parse back: the output of one node is one line, and the
 * assertions below are written against those lines as strings. What is pinned
 * is what a customer would notice reading their own config - which nodes are in
 * it, how each one is spelled, and what the panel above the list says.
 */
class SurgeRendererTest {

    private static final SubscriptionTemplates TEMPLATES =
        new SubscriptionTemplates(SubscriptionFixtures.mapper());

    private static RenderedConfig render(boolean surfboard, List<NodeClientView> nodes) {
        return render(surfboard, nodes, SubscriptionFixtures.USAGE);
    }

    private static RenderedConfig render(
        boolean surfboard,
        List<NodeClientView> nodes,
        SubscriptionUsage usage
    ) {
        SubscriptionTemplates.Kind kind = surfboard
            ? SubscriptionTemplates.Kind.SURFBOARD
            : SubscriptionTemplates.Kind.SURGE;
        return (surfboard ? SurgeRenderer.surfboard() : SurgeRenderer.surge())
            .render(
                SubscriptionFixtures.request(kind, TEMPLATES.bundled(kind), usage),
                nodes
            );
    }

    private static RenderedConfig surge() {
        return render(false, SubscriptionFixtures.nodes());
    }

    private static RenderedConfig surfboard() {
        return render(true, SubscriptionFixtures.nodes());
    }

    /** The line one node was written as, or null when the format left it out. */
    private static String line(RenderedConfig rendered, String name) {
        return rendered.body().lines()
            .filter(entry -> entry.startsWith(name + "=") || entry.startsWith(name + " = "))
            .findFirst()
            .orElse(null);
    }

    /**
     * Which of the fixture nodes a format wrote an entry for.
     *
     * The template's own lines carry an {@code =} too, so this asks about the
     * node names rather than trying to pick proxy entries out of the text.
     */
    private static List<String> carried(RenderedConfig rendered, List<String> candidates) {
        return candidates.stream()
            .filter(name -> line(rendered, name) != null)
            .toList();
    }

    private static final List<String> FIXTURE_NAMES = List.of(
        "香港 01", "SS2022", "vmess-ws", "vless-reality", "trojan-grpc",
        "hy2", "tuic", "anytls", "socks", "http"
    );

    // ------------------------------------------------------------------
    // The one line that carries every difference between the two
    // ------------------------------------------------------------------

    /**
     * The spacing around the name separator is how each client writes its own
     * sample configs, and an administrator comparing this output with the
     * manual is entitled to see the same thing.
     */
    @Test
    void theTwoFormatsSpellTheNameSeparatorDifferently() {
        assertThat(line(surge(), "香港 01")).isEqualTo(
            "香港 01 = ss,hk1.example.com,8443,encrypt-method=aes-256-gcm,"
                + "password=" + SubscriptionFixtures.IDENTITY
                + ",tfo=true,udp-relay=true,obfs=http,obfs-host=www.bing.com"
        );
        assertThat(line(surfboard(), "香港 01")).isEqualTo(
            "香港 01=ss,hk1.example.com,8443,encrypt-method=aes-256-gcm,"
                + "password=" + SubscriptionFixtures.IDENTITY
                + ",tfo=true,udp-relay=true,obfs=http,obfs-host=www.bing.com"
        );
    }

    /**
     * Surfboard's parser knows the 2022 chacha cipher and Surge's does not, so
     * the same node appears in one output and not the other.
     */
    @Test
    void aCipherOneClientCannotEncryptWithLeavesThatOutputAlone() {
        NodeClientView node = SubscriptionFixtures.node(
            "SS2022-chacha", "shadowsocks", "hk3.example.com", 8445, SubscriptionFixtures.IDENTITY,
            Map.of("cipher", "2022-blake3-chacha20-poly1305")
        );

        assertThat(line(render(true, List.of(node)), "SS2022-chacha")).isNotNull();
        assertThat(line(render(false, List.of(node)), "SS2022-chacha")).isNull();
    }

    // ------------------------------------------------------------------
    // Which nodes make it in
    // ------------------------------------------------------------------

    /**
     * Surge speaks seven protocols and Surfboard four; neither speaks vless or
     * tuic, which neither client has ever implemented.
     */
    @Test
    void eachFormatCarriesItsOwnProtocolSet() {
        assertThat(carried(surge(), FIXTURE_NAMES)).containsExactly(
            "香港 01", "SS2022", "vmess-ws", "trojan-grpc", "hy2",
            "anytls", "socks", "http"
        );
        assertThat(carried(surfboard(), FIXTURE_NAMES)).containsExactly(
            "香港 01", "SS2022", "vmess-ws", "trojan-grpc", "anytls"
        );
    }

    /**
     * Surge has no Hysteria 1 support at all, and the original answers with an
     * empty entry rather than a best-effort one - a node that cannot work is
     * worse in a client's list than one that is simply absent.
     */
    @Test
    void hysteriaOneIsLeftOutRatherThanDowngraded() {
        assertThat(line(surge(), "hy2")).isNotNull();
        assertThat(line(render(false, List.of(SubscriptionFixtures.hysteria1())), "hy1"))
            .isNull();
    }

    /**
     * Surge refuses a proxy line carrying options it does not know, and its own
     * sample for AnyTLS lists neither flag; Surfboard takes both.
     */
    @Test
    void anytlsCarriesTheTransportFlagsForSurfboardAlone() {
        assertThat(line(surge(), "anytls")).isEqualTo(
            "anytls = anytls,a.example.com,443,password=" + SubscriptionFixtures.IDENTITY
                + ",sni=sni.example.com,skip-cert-verify=true"
        );
        assertThat(line(surfboard(), "anytls")).isEqualTo(
            "anytls=anytls,a.example.com,443,password=" + SubscriptionFixtures.IDENTITY
                + ",tfo=true,udp-relay=true,sni=sni.example.com,skip-cert-verify=true"
        );
    }

    @Test
    void trojanWritesItsTrustSettingsOnlyWhenTheNodeAskedForThem() {
        assertThat(line(surge(), "trojan-grpc")).isEqualTo(
            "trojan-grpc = trojan,t.example.com,443,password="
                + SubscriptionFixtures.IDENTITY
                + ",sni=sni.example.com,tfo=true,udp-relay=true"
        );
    }

    /**
     * The trust settings come before the transport, and the certificate check
     * is skipped only when the node asked for it - the fixture's does not.
     */
    @Test
    void vmessWritesItsWebsocketTransport() {
        assertThat(line(surge(), "vmess-ws")).isEqualTo(
            "vmess-ws = vmess,v.example.com,443,username=" + SubscriptionFixtures.IDENTITY
                + ",vmess-aead=true,tfo=true,udp-relay=true,tls=true"
                + ",sni=sni.example.com"
                + ",ws=true,ws-path=/ws,ws-headers=Host:cdn.example.com"
        );
    }

    /** Socks and http carry the account identity as both halves of a pair. */
    @Test
    void socksAndHttpCarryTheCredentialTwice() {
        assertThat(line(surge(), "socks")).isEqualTo(
            "socks = socks5,s.example.com,1080," + SubscriptionFixtures.IDENTITY
                + "," + SubscriptionFixtures.IDENTITY + ",udp-relay=true"
        );
        assertThat(line(surge(), "http")).isEqualTo(
            "http = http,p.example.com,8080," + SubscriptionFixtures.IDENTITY
                + "," + SubscriptionFixtures.IDENTITY
        );
    }

    // ------------------------------------------------------------------
    // The panel and the placeholders
    // ------------------------------------------------------------------

    /**
     * The line breaks are a literal backslash and {@code n}. These clients read
     * a config value up to the end of the line, so a real newline here would
     * truncate the panel and orphan the rest of it.
     */
    @Test
    void theSubscriptionPanelIsWrittenWithLiteralLineBreaks() {
        assertThat(surge().body()).contains(
            "SubscribeInfo = title=SinX Cloud订阅信息, content=上传流量：1GB"
                + "\\n下载流量：0.5GB"
                + "\\n剩余流量：2.5GB"
                + "\\n套餐流量：4GB"
                + "\\n到期时间：2027-01-01 08:00:00, style=info"
        );
    }

    /** An account with no expiry is told so rather than shown a 1970 date. */
    @Test
    void anAccountWithNoExpirySaysSo() {
        RenderedConfig rendered = render(
            false,
            SubscriptionFixtures.nodes(),
            SubscriptionFixtures.NEVER_EXPIRES
        );

        assertThat(rendered.body()).contains("到期时间：长期有效");
        assertThat(rendered.body()).contains("剩余流量：8GB");
    }

    @Test
    void everyPlaceholderIsSubstituted() {
        String body = surge().body();

        assertThat(body).doesNotContain("$subs_link", "$subs_domain", "$proxies",
            "$proxy_group", "$subscribe_info");
        assertThat(body).contains("MANAGED-CONFIG " + SubscriptionFixtures.SUBSCRIPTION_URL);
        assertThat(body).contains("DOMAIN," + SubscriptionFixtures.REQUEST_HOST + ",DIRECT");
        assertThat(body).contains(
            "Proxy = select, auto, fallback, 香港 01, SS2022, vmess-ws, trojan-grpc, "
                + "hy2, anytls, socks, http"
        );
    }

    /**
     * The group is the node list, and it ends at the last name - the original
     * builds it by appending {@code ", "} after every node and trimming what is
     * left over.
     */
    @Test
    void theProxyGroupTrailsIntoTheRestOfItsLine() {
        assertThat(surfboard().body()).contains(
            "Proxy = select, auto, fallback, 香港 01, SS2022, vmess-ws, trojan-grpc, anytls"
        );
    }

    @Test
    void theHeadersTellTheClientWhatItFetched() {
        String attachment =
            "attachment;filename*=UTF-8''SinX%20Cloud.conf";

        assertThat(surge().contentType()).isEqualTo("application/octet-stream");
        assertThat(surge().headers()).isEqualTo(Map.of("content-disposition", attachment));
        // The original sets no type for Surfboard at all, and the framework
        // then wrote text/html into a .conf download. It gets the plain type.
        assertThat(surfboard().contentType()).isEqualTo("text/plain");
        assertThat(surfboard().headers()).isEqualTo(Map.of("content-disposition", attachment));
    }
}
