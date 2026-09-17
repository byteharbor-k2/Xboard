package com.sinx.platform.subscription.client;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The encoding rules the original panel's v2ray links are written in.
 *
 * These are not "roughly URL encoding". Two different encoders appear in the
 * same link - the query string uses one, the {@code #fragment} another - and a
 * client that has been handed a link by this panel before will be handed a
 * byte-identical one today. Reproducing the exact rule matters because a node
 * name with a space or an asterisk in it is entirely ordinary, and the two
 * encoders disagree about both.
 *
 * <ul>
 *   <li>{@link #query} leaves alphanumerics and {@code - _ .} alone, writes a
 *       space as {@code +}, and percent-encodes everything else including
 *       {@code *} and {@code ~}.</li>
 *   <li>{@link #fragment} leaves the RFC 3986 unreserved set
 *       ({@code A-Z a-z 0-9 - _ . ~}) alone and writes a space as {@code %20}.</li>
 * </ul>
 */
final class V2rayUriEncoding {

    private static final String QUERY_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.";
    private static final String FRAGMENT_SAFE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private V2rayUriEncoding() {
    }

    /**
     * A query string in the original's shape.
     *
     * PHP's {@code http_build_query} is doing this, and it has opinions: a
     * parameter whose value is null is left out entirely, while one whose value
     * is an empty string is written with nothing after the {@code =}. Booleans
     * arrive as {@code 1} and {@code 0} rather than as words. A caller that
     * builds a map of parameters gets the original's output, not a tidier one.
     */
    static String query(Map<String, ?> parameters) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, ?> parameter : parameters.entrySet()) {
            if (parameter.getValue() == null) {
                continue;
            }
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(parameter.getKey(), QUERY_SAFE, true));
            query.append('=');
            query.append(encode(stringify(parameter.getValue()), QUERY_SAFE, true));
        }
        return query.toString();
    }

    /**
     * One value in the same dialect {@link #query} writes.
     *
     * Used for the fragment of a vless link, which the original encodes with
     * {@code urlencode} while encoding every other link's fragment with
     * {@code rawurlencode}. The two disagree about a space, and a client that
     * has shown "A B" for one node should not start showing "A+B".
     */
    static String formEncode(String value) {
        return encode(value, QUERY_SAFE, true);
    }

    /**
     * A node name for the {@code #fragment} at the end of a link.
     *
     * {@code rawurlencode}, so a space stays {@code %20} and a tilde stays a
     * tilde.
     */
    static String fragment(String value) {
        return encode(value, FRAGMENT_SAFE, false);
    }

    /** {@code rawurlencode} on its own, for the places outside a link that use it. */
    static String rawUrlEncode(String value) {
        return encode(value, FRAGMENT_SAFE, false);
    }

    /**
     * A host as it belongs in the authority of a link: a bare IPv6 address has
     * to be bracketed or the colons read as the port separator, and anything
     * else - a name, an IPv4 address, a host that is already bracketed - is
     * left exactly as the administrator typed it.
     */
    static String host(String value) {
        return isIpv6Literal(value) ? "[" + value + "]" : value;
    }

    /**
     * Whether this is a bare IPv6 literal.
     *
     * Deliberately strict, because both answers have a cost: bracketing a name
     * breaks the link, and failing to bracket an address breaks it differently.
     * An address that is already bracketed is not a literal by this test, which
     * is what keeps it from being bracketed twice.
     */
    static boolean isIpv6Literal(String value) {
        if (value == null || value.indexOf(':') < 0) {
            return false;
        }
        String address = value;
        boolean embedded = address.contains(".");
        if (embedded) {
            // A trailing dotted quad stands in for the last two groups. Putting
            // two empty groups in its place leaves the colons around it alone,
            // so a "::" in front of the quad is still a "::".
            int separator = address.lastIndexOf(':');
            if (separator < 0 || !isIpv4(address.substring(separator + 1))) {
                return false;
            }
            address = address.substring(0, separator) + ":0:0";
        }
        int compression = address.indexOf("::");
        if (compression >= 0 && address.indexOf("::", compression + 1) >= 0) {
            return false;
        }
        String head = compression < 0 ? address : address.substring(0, compression);
        String tail = compression < 0
            ? ""
            : address.substring(compression + 2);
        int headGroups = countGroups(head);
        int tailGroups = countGroups(tail);
        if (headGroups < 0 || tailGroups < 0) {
            return false;
        }
        int capacity = 8 - (embedded ? 2 : 0);
        // A "::" has to stand for at least one group, or it would not be there.
        return compression < 0
            ? headGroups == capacity
            : headGroups + tailGroups < capacity;
    }

    private static int countGroups(String part) {
        if (part.isEmpty()) {
            return 0;
        }
        int groups = 0;
        for (String group : part.split(":", -1)) {
            if (group.isEmpty() || group.length() > 4 || !isHex(group)) {
                return -1;
            }
            groups++;
        }
        return groups;
    }

    private static boolean isIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                if (!Character.isDigit(octet.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.digit(value.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String stringify(Object value) {
        if (value instanceof Boolean flag) {
            return flag ? "1" : "0";
        }
        return String.valueOf(value);
    }

    private static String encode(String value, String safe, boolean plusForSpace) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder encoded = new StringBuilder(value.length());
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int character = raw & 0xFF;
            if (character == ' ' && plusForSpace) {
                encoded.append('+');
            } else if (character < 0x80 && safe.indexOf(character) >= 0) {
                encoded.append((char) character);
            } else {
                encoded.append('%');
                encoded.append(HEX[character >> 4]);
                encoded.append(HEX[character & 0x0F]);
            }
        }
        return encoded.toString();
    }
}
