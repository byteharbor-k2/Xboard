package com.sinx.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The PHP behaviours the Epay signature leans on.
 *
 * Every expectation here is what {@code php -r} prints for the same input, so a
 * future reader can tell a deliberate rule from a coincidence.
 */
class PhpCompatTest {

    @Test
    void buildsTheSignedStringInByteOrderWithoutEncodingValues() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("type", "alipay");
        params.put("pid", "1000");
        params.put("money", "12.34");

        // PHP's http_build_query percent-encodes and its urldecode undoes it, so
        // the two cancel and the values are hashed as written.
        assertThat(PhpCompat.signedString(params))
            .isEqualTo("money=12.34&pid=1000&type=alipay");
    }

    @Test
    void unescapesOneLevelOfBackslashesAsPhpDoes() {
        // stripslashes("a=x\\y") is "a=xy", and a trailing backslash is dropped.
        assertThat(PhpCompat.signedString(Map.of("a", "x\\y", "b", "plain")))
            .isEqualTo("a=xy&b=plain");
        assertThat(PhpCompat.signedString(Map.of("a", "trailing\\", "b", "e")))
            .isEqualTo("a=trailing&b=e");
    }

    @Test
    void encodesTheWayPhpEncodesFormValues() {
        // php -r 'echo http_build_query(["a" => "a~b*c d", "b" => "++"]);'
        assertThat(PhpCompat.urlEncode("a~b*c d")).isEqualTo("a%7Eb%2Ac+d");
        assertThat(PhpCompat.urlEncode("++")).isEqualTo("%2B%2B");
        assertThat(PhpCompat.urlEncode("x\\y")).isEqualTo("x%5Cy");
        assertThat(PhpCompat.urlEncode("AZaz09-_. ")).isEqualTo("AZaz09-_.+");
    }

    @Test
    void encodesMultibyteTextAsItsUtf8Bytes() {
        // php -r 'echo http_build_query(["b" => "中文"]);'
        assertThat(PhpCompat.urlEncode("中文"))
            .isEqualTo("%E4%B8%AD%E6%96%87");
    }

    @Test
    void hashesToLowercaseHexMd5() {
        assertThat(PhpCompat.md5("hello"))
            .isEqualTo("5d41402abc4b2a76b9719d911017c592");
    }

    @Test
    void comparesSignaturesWithoutShortCircuitingOnTheFirstDifference() {
        assertThat(PhpCompat.sameSignature("abc", "abc")).isTrue();
        assertThat(PhpCompat.sameSignature("abc", "abd")).isFalse();
        assertThat(PhpCompat.sameSignature("abc", "abcd")).isFalse();
        assertThat(PhpCompat.sameSignature(null, "abc")).isFalse();
        assertThat(PhpCompat.sameSignature("abc", null)).isFalse();
    }
}
