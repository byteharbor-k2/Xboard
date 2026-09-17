package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The two encoders the v2ray links are written in. The original panel hands a
 * client one dialect of {@code urlencode} for the query and another for the
 * fragment, and a client that already imported a link must be handed the same
 * bytes next time.
 */
class V2rayUriEncodingTest {

    @Test
    void aQueryWritesASpaceAsItsPlus() {
        assertThat(V2rayUriEncoding.query(Map.of("ps", "香港 01")))
            .isEqualTo("ps=%E9%A6%99%E6%B8%AF+01");
    }

    @Test
    void aFragmentWritesASpaceAsPercentTwenty() {
        assertThat(V2rayUriEncoding.fragment("香港 01"))
            .isEqualTo("%E9%A6%99%E6%B8%AF%2001");
    }

    @Test
    void aTildeSurvivesAFragmentAndNotAQuery() {
        assertThat(V2rayUriEncoding.fragment("a~b")).isEqualTo("a~b");
        assertThat(V2rayUriEncoding.query(Map.of("ps", "a~b"))).isEqualTo("ps=a%7Eb");
    }

    @Test
    void anAsteriskIsEncodedEitherWay() {
        assertThat(V2rayUriEncoding.fragment("a*b")).isEqualTo("a%2Ab");
        assertThat(V2rayUriEncoding.query(Map.of("ps", "a*b"))).isEqualTo("ps=a%2Ab");
    }

    /** PHP's {@code http_build_query} leaves a null out and keeps an empty string. */
    @Test
    void aNullParameterIsLeftOutAndAnEmptyOneIsNot() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("sni", null);
        parameters.put("path", "");
        parameters.put("flow", "xtls-rprx-vision");

        assertThat(V2rayUriEncoding.query(parameters))
            .isEqualTo("path=&flow=xtls-rprx-vision");
    }

    @Test
    void aBooleanIsWrittenAsOneOrZero() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("insecure", true);
        parameters.put("secure", false);

        assertThat(V2rayUriEncoding.query(parameters)).isEqualTo("insecure=1&secure=0");
    }

    @Test
    void parametersKeepTheOrderTheyWereBuiltIn() {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("mode", "multi");
        parameters.put("security", "");
        parameters.put("type", "ws");

        assertThat(V2rayUriEncoding.query(parameters)).isEqualTo("mode=multi&security=&type=ws");
    }

    @Test
    void theEncodingIsTheSameInBothDirectionsForOrdinaryText() {
        assertThat(V2rayUriEncoding.formEncode("HK 01")).isEqualTo("HK+01");
        assertThat(V2rayUriEncoding.fragment("HK 01")).isEqualTo("HK%2001");
        assertThat(V2rayUriEncoding.rawUrlEncode("Xboard Site")).isEqualTo("Xboard%20Site");
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
            "example.com        | false",
            "127.0.0.1          | false",
            "::1                | true",
            "2001:db8::1        | true",
            "2001:db8:0:0:0:0:0:1 | true",
            "::ffff:1.2.3.4     | true",
            "[2001:db8::1]      | false",
            "2001:db8:0:0:0:0:0:1:2 | false",
            "1:2:3:4:5:6:7:8::  | false",
            "gggg::1            | false",
            "2001:db8::zz        | false",
            "host:8080          | false",
            "a::b::c            | false"
        }
    )
    void onlyABareIpv6LiteralIsRecognised(String value, boolean literal) {
        assertThat(V2rayUriEncoding.isIpv6Literal(value)).isEqualTo(literal);
    }

    @ParameterizedTest
    @ValueSource(strings = { "example.com", "127.0.0.1", "[2001:db8::1]" })
    void aHostThatIsNotABareLiteralIsLeftAlone(String value) {
        assertThat(V2rayUriEncoding.host(value)).isEqualTo(value);
    }

    @Test
    void aBareIpv6AddressIsBracketed() {
        assertThat(V2rayUriEncoding.host("2001:db8::1")).isEqualTo("[2001:db8::1]");
        assertThat(V2rayUriEncoding.host("::1")).isEqualTo("[::1]");
    }
}
