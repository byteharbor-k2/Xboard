package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.sinx.platform.configuration.application.SubscriptionTemplates;

/**
 * Which client gets which config.
 *
 * The order the formats are asked in is the test that matters: {@code
 * clash-verge} and {@code clashmetaforandroid} both contain a substring that
 * selects the narrow Clash format, and if that format were asked first those
 * clients would be served a config their own features do not fit in.
 */
class ClientFormatResolverTest {

    private final ClientFormatResolver resolver =
        new ClientFormatResolver(SubscriptionFixtures.mapper());

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "clash                          | clash",
            "Clash                          | clash",
            "clash-verge                    | meta",
            "clash-verge/v1.7.7             | meta",
            "clashmetaforandroid            | meta",
            "Clash.Meta                     | meta",
            "mihomo                         | meta",
            "flclash                        | meta",
            "nekobox                        | meta",
            "sing-box                       | sing-box",
            "singbox                        | sing-box",
            "Hiddify/1.2.3                  | sing-box",
            "v2rayN/6.31                    | general",
            "v2rayNG/1.8.5                  | general",
            "Shadowrocket/2.2.29            | general",
            "Mozilla/5.0 (Macintosh)        | general"
        }
    )
    void aClientIsRecognisedByItsName(String userAgent, String expected) {
        assertThat(resolver.resolve(userAgent).name()).isEqualTo(expected);
    }

    /** The narrow format's own flag is the only one that selects it. */
    @Test
    void aBareClashUserAgentDoesNotBecomeMihomo() {
        assertThat(resolver.resolve("clash").name()).isEqualTo("clash");
        assertThat(resolver.resolve("Clash for Windows").name()).isEqualTo("clash");
    }

    /**
     * A client the panel has never heard of gets base64 links, because a client
     * that reads nothing gets nothing from a Clash config it cannot parse.
     */
    @Test
    void anUnknownOrAbsentClientFallsBackToTheGenericList() {
        assertThat(resolver.resolve(null).name()).isEqualTo("general");
        assertThat(resolver.resolve("").name()).isEqualTo("general");
        assertThat(resolver.resolve("   ").name()).isEqualTo("general");
        assertThat(resolver.resolve("curl/8.4.0").name()).isEqualTo("general");
    }

    @Test
    void theGenericListIsAlsoASelectableFormat() {
        assertThat(resolver.resolve("general").templateKind()).isNull();
        assertThat(resolver.formats()).extracting(ClientFormat::name)
            .containsExactly("meta", "clash", "sing-box", "general");
    }

    @Test
    void everyFormatNamesTheTemplateItRendersInto() {
        assertThat(resolver.resolve("clash").templateKind())
            .isEqualTo(SubscriptionTemplates.Kind.CLASH);
        assertThat(resolver.resolve("meta").templateKind())
            .isEqualTo(SubscriptionTemplates.Kind.CLASH_META);
        assertThat(resolver.resolve("sing-box").templateKind())
            .isEqualTo(SubscriptionTemplates.Kind.SING_BOX);
    }

    /**
     * A format is only offered the protocols it can express. The narrow Clash
     * clients cannot build a vless or hysteria proxy at all, so handing them one
     * would produce a config that fails to load rather than one with a node
     * missing.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "shadowsocks", "vmess", "trojan", "socks", "http"
    })
    void theNarrowFormatExpressesTheOldProtocols(String protocol) {
        assertThat(resolver.resolve("clash").accepts(protocol)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "vless", "hysteria", "tuic", "anytls", "mieru"
    })
    void theNarrowFormatRefusesTheNewOnes(String protocol) {
        assertThat(resolver.resolve("clash").accepts(protocol)).isFalse();
        assertThat(resolver.resolve("meta").accepts(protocol)).isTrue();
    }

    @Test
    void singBoxAndTheGenericListRefuseTheProtocolNeitherCanCarry() {
        assertThat(resolver.resolve("sing-box").accepts("mieru")).isFalse();
        assertThat(resolver.resolve("general").accepts("mieru")).isFalse();
        assertThat(resolver.resolve("general").accepts("vless")).isTrue();
    }
}
