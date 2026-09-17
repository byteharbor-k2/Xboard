package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The v2ray link list, pinned link for link.
 *
 * These are the exact bytes the original's {@code General} protocol emits, so
 * the whole point of this test is that they stay exact: the links carry two
 * different percent-encoders, a base64 block that is a JSON object rather than
 * a URL, and a CRLF after every line. A "tidier" rewrite of any of those would
 * still parse, which is why they are written out in full here.
 */
class GenericUriRendererTest {

    private static final String ID = SubscriptionFixtures.IDENTITY;
    private static final String JOINED =
        "MTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU1NTU1OjExMTExMTExLTIyMjItMzMzMy00NDQ0LTU1NTU1NTU1NTU1NQ==";

    private static List<String> lines(List<NodeClientView> nodes) {
        String body = new GenericUriRenderer(SubscriptionFixtures.mapper())
            .render(SubscriptionFixtures.genericRequest(), nodes)
            .body();
        return List.of(
            new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8)
                .split("\r\n")
        );
    }

    private static String line(NodeClientView node) {
        return lines(List.of(node)).getFirst();
    }

    @Test
    void shadowsocksJoinsItsCipherAndPasswordInTheUrlSafeAlphabet() {
        assertThat(line(SubscriptionFixtures.shadowsocks())).isEqualTo(
            "ss://YWVzLTI1Ni1nY206MTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU1NTU1"
                + "@hk1.example.com:8443"
                + "/?plugin=obfs%3Bobfs%3Dhttp%3Bobfs-host%3Dwww.bing.com"
                + "#%E9%A6%99%E6%B8%AF%2001"
        );
    }

    @Test
    void shadowsocks2022CarriesTheJoinedTwoHalves() {
        assertThat(line(SubscriptionFixtures.shadowsocks2022())).isEqualTo(
            "ss://MjAyMi1ibGFrZTMtYWVzLTEyOC1nY206REo4R21aN2Y2a1E4aFF2UFEyWjhyQTpNVEV4TVRFeE1URXRNakl5TWkwek13PT0"
                + "@hk2.example.com:8444#SS2022"
        );
    }

    @Test
    void vmessIsABase64JsonObject() {
        assertThat(line(SubscriptionFixtures.vmess())).isEqualTo(
            "vmess://eyJ2IjoiMiIsInBzIjoidm1lc3Mtd3MiLCJhZGQiOiJ2LmV4YW1wbGUuY29tIiwicG9ydCI6"
                + "IjQ0MyIsImlkIjoiMTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU1NTU1IiwiYWlkIjoiMCIs"
                + "Im5ldCI6IndzIiwidHlwZSI6IndzIiwiaG9zdCI6ImNkbi5leGFtcGxlLmNvbSIsInBhdGgiOiIvd3Mi"
                + "LCJ0bHMiOiJ0bHMiLCJzbmkiOiJzbmkuZXhhbXBsZS5jb20ifQ=="
        );
    }

    /**
     * The vless fragment is encoded with {@code urlencode} where every other
     * link's is {@code rawurlencode} - so a space would be a {@code +} here and
     * a {@code %20} everywhere else. This name has no space in it; the
     * difference is held down by {@link V2rayUriEncodingTest}.
     */
    @Test
    void vlessRealityDescribesItselfInQueryParameters() {
        assertThat(line(SubscriptionFixtures.vless())).isEqualTo(
            "vless://" + ID + "@r.example.com:443"
                + "?mode=multi&security=reality&encryption=none&type=tcp"
                + "&flow=xtls-rprx-vision&pbk=PBK&sid=abcd"
                + "&sni=www.apple.com&servername=www.apple.com&spx=%2F"
                + "#vless-reality"
        );
    }

    @Test
    void trojanWritesItsAllowInsecureEvenWhenItIsFalse() {
        assertThat(line(SubscriptionFixtures.trojan())).isEqualTo(
            "trojan://" + ID + "@t.example.com:443"
                + "?allowInsecure=0&peer=sni.example.com&sni=sni.example.com"
                + "&type=grpc&serviceName=gsvc"
                + "#trojan-grpc"
        );
    }

    @Test
    void hysteria2CarriesItsPasswordInTheAuthority() {
        assertThat(line(SubscriptionFixtures.hysteria())).isEqualTo(
            "hysteria2://" + ID + "@h.example.com:443"
                + "?sni=sni.example.com&insecure=0&obfs=salamander&obfs-password=obfspw"
                + "#hy2"
        );
    }

    /**
     * Hysteria 1 is the other shape: the password moves into {@code auth}, the
     * bandwidth hints come back, and - unlike sing-box's hysteria 1, see
     * {@link SingBoxRendererTest} - the obfs is left out of a node that did not
     * open it. This node's fixture has it closed on purpose, so the two
     * renderers disagreeing is pinned rather than assumed.
     */
    @Test
    void hysteria1CarriesItsPasswordInAuthAndKeepsItsBandwidthHints() {
        assertThat(line(SubscriptionFixtures.hysteria1())).isEqualTo(
            "hysteria://h1.example.com:8443"
                + "?sni=sni.example.com&insecure=1&protocol=udp&auth=" + ID
                + "&upmbps=100&downmbps=200"
                + "#hy1"
        );
    }

    @Test
    void tuicRepeatsTheCredentialAsBothUserAndPassword() {
        assertThat(line(SubscriptionFixtures.tuic())).isEqualTo(
            "tuic://" + ID + ':' + ID + "@u.example.com:443"
                + "?sni=sni.example.com&alpn=h3"
                + "&congestion_control=bbr&udp-relay-mode=native"
                + "#tuic"
        );
    }

    @Test
    void anytlsWritesInsecureFromTheNodeAsItStoredIt() {
        assertThat(line(SubscriptionFixtures.anytls())).isEqualTo(
            "anytls://" + ID + "@a.example.com:443"
                + "?sni=sni.example.com&insecure=1"
                + "#anytls"
        );
    }

    @Test
    void socksAndHttpCarryABasicAuthPairAndNothingElse() {
        assertThat(line(SubscriptionFixtures.socks()))
            .isEqualTo("socks://" + JOINED + "@s.example.com:1080#socks");
        assertThat(line(SubscriptionFixtures.http()))
            .isEqualTo("http://" + JOINED + "@p.example.com:8080#http");
    }

    // ------------------------------------------------------------------
    // The body around the links
    // ------------------------------------------------------------------

    @Test
    void theWholeBodyIsBase64OfTheLinksInOrder() {
        List<String> lines = lines(SubscriptionFixtures.nodes());

        assertThat(lines).hasSize(10);
        assertThat(lines.getFirst()).startsWith("ss://");
        assertThat(lines.get(1)).startsWith("ss://");
        assertThat(lines.getLast()).startsWith("http://");
    }

    @Test
    void everyLinkEndsWithTheCarriageReturnNoClientNeeds() {
        String body = new GenericUriRenderer(SubscriptionFixtures.mapper())
            .render(SubscriptionFixtures.genericRequest(), List.of(
                SubscriptionFixtures.socks(), SubscriptionFixtures.http()
            ))
            .body();
        String decoded = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);

        assertThat(decoded).endsWith("\r\n");
        assertThat(decoded.split("\r\n")).hasSize(2);
    }

    @Test
    void theGenericFormatAddsNoHeadersOfItsOwn() {
        RenderedConfig rendered = new GenericUriRenderer(SubscriptionFixtures.mapper())
            .render(SubscriptionFixtures.genericRequest(), SubscriptionFixtures.nodes());

        assertThat(rendered.contentType()).isEqualTo("text/plain");
        assertThat(rendered.headers()).isEmpty();
    }

    @Test
    void aProtocolTheGenericFormatCannotExpressIsSkipped() {
        NodeClientView mieru = SubscriptionFixtures.node(
            "mieru", "mieru", "m.example.com", 443, ID, java.util.Map.of()
        );

        assertThat(lines(List.of(mieru, SubscriptionFixtures.socks()))).hasSize(1);
    }
}
