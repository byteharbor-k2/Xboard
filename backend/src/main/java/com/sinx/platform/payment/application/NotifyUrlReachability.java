package com.sinx.platform.payment.application;

import java.net.URI;
import java.util.Locale;

/**
 * Whether a notify URL is one a remote payment gateway can actually reach.
 *
 * A gateway sits on another company's servers. It can follow any address the
 * internet can route to, but it cannot reach this machine by its local names
 * or its local networks: a notify URL that points at localhost, a loopback
 * address or a private network is accepted, handed to the gateway, and never
 * answered, so an order sits awaiting a callback that will never arrive.
 *
 * The check is deliberately conservative - it only calls an address
 * unreachable when the string itself shows why. A domain name is left alone,
 * because whether it resolves to a private address cannot be seen from the
 * string, and a URL without a host is not judged at all.
 */
public final class NotifyUrlReachability {

    private NotifyUrlReachability() {
    }

    /**
     * @param url the address a gateway would be told to report to
     * @return true when a remote gateway can plausibly reach it - including
     *     when the URL does not carry a host this class can judge
     */
    public static boolean reachableByRemoteGateway(String url) {
        return unreachableReason(url) == null;
    }

    /**
     * Why a remote gateway cannot reach this address.
     *
     * @param url the address a gateway would be told to report to
     * @return {@code "localhost"}, {@code "local-domain"}, {@code "loopback"},
     *     {@code "unspecified"}, {@code "private-network"} or {@code
     *     "link-local"} - or null when the address is none of those, or the
     *     URL is malformed or carries no host
     */
    public static String unreachableReason(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        if (host == null || host.isBlank()) {
            return null;
        }
        String name = host.toLowerCase(Locale.ROOT);
        // A bracketed IPv6 literal, as a URI carries one.
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.equals("localhost")) {
            return "localhost";
        }
        if (name.endsWith(".local")) {
            return "local-domain";
        }
        if (name.equals("::1")) {
            return "loopback";
        }
        if (name.equals("0.0.0.0")) {
            return "unspecified";
        }
        Long address = ipv4(name);
        if (address != null) {
            if (inRange(address, 0x7f00_0000L, 8)) {
                return "loopback";
            }
            if (inRange(address, 0x0a00_0000L, 8)) {
                return "private-network";
            }
            if (inRange(address, 0xac10_0000L, 12)) {
                return "private-network";
            }
            if (inRange(address, 0xc0a8_0000L, 16)) {
                return "private-network";
            }
            if (inRange(address, 0xa9fe_0000L, 16)) {
                return "link-local";
            }
        }
        return null;
    }

    /** The four octets packed into one number, or null when not an IPv4 literal. */
    private static Long ipv4(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        long value = 0L;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int octet = 0;
            for (int i = 0; i < part.length(); i++) {
                char digit = part.charAt(i);
                if (digit < '0' || digit > '9') {
                    return null;
                }
                octet = octet * 10 + (digit - '0');
            }
            if (octet > 255) {
                return null;
            }
            value = (value << 8) | octet;
        }
        return value;
    }

    private static boolean inRange(long address, long network, int prefixLength) {
        long mask = prefixLength == 0 ? 0L : (~0L) << (32 - prefixLength);
        return (address & mask) == (network & mask);
    }
}
