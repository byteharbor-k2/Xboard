package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule that keeps a node named {@code DIRECT} from being read as a regex,
 * and the looser one sing-box needs because it has no such ambiguity.
 */
class SubscriptionNamePatternTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "/香港|HK|HongKong|Hong Kong/i",
        "/美国|US/i",
        "#tw#i",
        "@japan@",
        "%sg%",
        "~tokyo~",
        "/hk\\/01/i",
        "//"
    })
    void aDelimitedMemberIsAPattern(String member) {
        assertThat(SubscriptionNamePattern.isDelimited(member)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DIRECT",
        "自动选择",
        "香港 01",
        "🇭🇰 香港",
        "ss-2022",
        "/",
        "/unclosed"
    })
    void aNodeNameIsNotAPattern(String member) {
        assertThat(SubscriptionNamePattern.isDelimited(member)).isFalse();
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = {
            "/香港|HK|HongKong|Hong Kong/i; 香港 01      ; true",
            "/香港|HK|HongKong|Hong Kong/i; Hong Kong 02 ; true",
            "/香港|HK|HongKong|Hong Kong/i; 日本 01      ; false",
            "/香港|HK/i; hk-01.example; true",
            "/美国|US/i; 新加坡 01; false",
            "/日本/i; 美国 03; false",
            "/(/i; anything; false"
        }
    )
    void aPatternMatchesAnywhereInTheName(String pattern, String name, boolean expected) {
        assertThat(SubscriptionNamePattern.matches(pattern, name)).isEqualTo(expected);
    }

    /**
     * A name that merely contains the keyword is enough. Anchoring would be the
     * obvious tidier rule and would silently drop every node an administrator
     * has ever named "香港 01".
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = {
            "香港; 香港 01; true",
            "香港; hongkong; false",
            "HK; hk 01; true",
            "直连; 直连节点; true",
            "DIRECT; direct; true",
            "/HK/i; hk 01; true",
            "/HK/i; 香港 01; false",
            "/HK|香港/i; 香港 01; true",
            "a~b; a~b; true",
            "a~b; axb; false"
        }
    )
    void aBareSingBoxPatternIsCaseInsensitive(String pattern, String name, boolean expected) {
        assertThat(SubscriptionNamePattern.matchesBare(pattern, name)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "(" })
    void anEmptyOrBrokenPatternMatchesNothing(String pattern) {
        assertThat(SubscriptionNamePattern.isDelimited(pattern)).isFalse();
        assertThat(SubscriptionNamePattern.matchesBare(pattern, "香港 01")).isFalse();
    }

    /**
     * Every delimiter sing-box unwraps still reaches the node. Each of these is
     * a pattern over "hk 01" written a different way, and all five spellings
     * have to match it.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/hk/i", "#hk#i", "~hk~i", "@hk@i", "%hk%i" })
    void everySingBoxDelimiterIsHonoured(String pattern) {
        assertThat(SubscriptionNamePattern.matchesBare(pattern, "hk 01")).isTrue();
    }

    /**
     * A delimiter sing-box does not recognise is not stripped, it is treated as
     * part of a bare pattern - so {@code -HK-} means the literal string "-HK-".
     */
    @ParameterizedTest
    @CsvSource(
        delimiter = ';',
        value = {
            "-HK-; hk 01; false",
            "-HK-; -hk- 01; true"
        }
    )
    void anUnrecognisedDelimiterIsLiteral(String pattern, String name, boolean expected) {
        assertThat(SubscriptionNamePattern.matchesBare(pattern, name)).isEqualTo(expected);
    }
}
