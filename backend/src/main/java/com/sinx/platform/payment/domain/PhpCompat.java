package com.sinx.platform.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * The handful of PHP behaviours the Epay signature depends on.
 *
 * Epay is a PHP application and the signature is defined in terms of what PHP's
 * {@code ksort}, {@code http_build_query}, {@code urldecode} and
 * {@code stripslashes} produce. Java's nearest equivalents are not the same
 * function - {@link java.net.URLEncoder} escapes {@code ~} as {@code %7E} but
 * leaves {@code *} alone and turns a space into a {@code +} only for the form
 * variant - so PHP's ASCII set is reproduced here rather than approximated.
 * Getting it wrong does not fail loudly; it fails as an intermittent "verify
 * error" on some merchants' parameters only.
 */
final class PhpCompat {

    private PhpCompat() {
    }

    /**
     * The string PHP's {@code stripslashes(urldecode(http_build_query($params)))}
     * produces: keys in byte order, {@code key=value} joined by {@code &}, with
     * each value unescaped by one level.
     *
     * The encode/decode pair in the original cancels out - {@code urldecode}
     * undoes exactly what {@code http_build_query} did - so what is left is the
     * values as they are, minus their backslashes.
     */
    static String signedString(Map<String, String> params) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry :
            new TreeMap<>(params).entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(entry.getKey())
                .append('=')
                .append(stripslashes(entry.getValue()));
        }
        return builder.toString();
    }

    /** PHP's {@code http_build_query}: RFC 1738, so a space is a {@code +}. */
    static String urlEncode(String value) {
        StringBuilder builder = new StringBuilder();
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int character = raw & 0xFF;
            boolean unreserved = (character >= 'a' && character <= 'z')
                || (character >= 'A' && character <= 'Z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_'
                || character == '.';
            if (unreserved) {
                builder.append((char) character);
            } else if (character == ' ') {
                builder.append('+');
            } else {
                builder.append('%')
                    .append(Character.toUpperCase(
                        Character.forDigit((character >> 4) & 0xF, 16)
                    ))
                    .append(Character.toUpperCase(
                        Character.forDigit(character & 0xF, 16)
                    ));
            }
        }
        return builder.toString();
    }

    /** PHP's {@code stripslashes}: a backslash escapes whatever follows it. */
    static String stripslashes(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\') {
                builder.append(character);
            } else if (index + 1 < value.length()) {
                builder.append(value.charAt(++index));
            }
        }
        return builder.toString();
    }

    /** Lowercase hex MD5, as PHP's {@code md5()} returns it. */
    static String md5(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("MD5 is required by Epay", missing);
        }
    }

    /** Compares signatures without leaking where they first differ. */
    static boolean sameSignature(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }
}
