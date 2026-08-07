package com.sinx.platform.node.application;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;

import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Validates the dynamic protocol_settings object accepted by the Xboard admin
 * API before it is persisted and sent to xboard-node.
 */
final class NodeProtocolSettingsValidator {

    private static final Set<String> TRANSPORTS = Set.of(
        "tcp", "tcp-http", "grpc", "ws", "h2", "httpupgrade", "xhttp"
    );
    private static final Set<String> MULTIPLEX_PROTOCOLS = Set.of(
        "smux", "yamux", "h2mux"
    );
    private static final Pattern DNS_LABEL = Pattern.compile(
        "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$"
    );
    private static final Pattern SAFE_TOKEN = Pattern.compile(
        "^[A-Za-z0-9._-]{1,255}$"
    );
    private static final Pattern SHORT_ID = Pattern.compile(
        "^(?:[0-9a-fA-F]{2}){0,8}$"
    );
    private static final Pattern BASE64_URL_KEY = Pattern.compile(
        "^[A-Za-z0-9_-]{43}=?$"
    );

    private NodeProtocolSettingsValidator() {
    }

    static void validate(String type, Map<String, Object> settings) {
        if (settings == null) {
            throw invalid("protocol_settings must be an object");
        }
        String listenIp = text(
            settings, "listen_ip", false, "protocol_settings.listen_ip"
        );
        if (!isBlank(listenIp)) {
            validateAddress(listenIp, "protocol_settings.listen_ip");
        }
        switch (type) {
            case "shadowsocks" -> validateShadowsocks(settings);
            case "vmess" -> validateVmess(settings);
            case "vless" -> validateVless(settings);
            case "trojan" -> validateTrojan(settings);
            case "hysteria" -> validateHysteria(settings);
            case "tuic" -> validateTuic(settings);
            case "socks" -> validateSocks(settings);
            case "naive", "http" -> validateSimpleTls(settings, type);
            case "mieru" -> validateMieru(settings);
            case "anytls" -> validateAnyTls(settings);
            default -> throw invalid("Unsupported node protocol");
        }
    }

    static void validateAddress(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw invalid(field + " is required and must not exceed 255 characters");
        }
        String candidate = value.trim();
        if (candidate.contains("://") || candidate.contains("/")
            || candidate.contains("?") || candidate.contains("#")
            || candidate.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(field + " must be a hostname or IP address without a scheme or path");
        }
        if (candidate.startsWith("[") && candidate.endsWith("]")) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.contains(":")) {
            try {
                if (!(InetAddress.getByName(candidate) instanceof Inet6Address)) {
                    throw invalid(field + " must be a valid hostname or IP address");
                }
                return;
            } catch (Exception exception) {
                throw invalid(field + " must be a valid hostname or IP address");
            }
        }
        if (candidate.matches("[0-9.]+")) {
            String[] parts = candidate.split("\\.", -1);
            if (parts.length != 4) {
                throw invalid(field + " must be a valid hostname or IP address");
            }
            for (String part : parts) {
                try {
                    if (part.isEmpty() || Integer.parseInt(part) > 255) {
                        throw invalid(field + " must be a valid hostname or IP address");
                    }
                } catch (NumberFormatException exception) {
                    throw invalid(field + " must be a valid hostname or IP address");
                }
            }
            return;
        }
        validateDomain(candidate, field);
    }

    private static void validateShadowsocks(Map<String, Object> settings) {
        text(settings, "cipher", true, "protocol_settings.cipher");
        text(settings, "obfs", false, "protocol_settings.obfs");
        text(settings, "plugin", false, "protocol_settings.plugin");
        text(settings, "plugin_opts", false, "protocol_settings.plugin_opts");
        text(settings, "client_fingerprint", false, "protocol_settings.client_fingerprint");
        Map<String, Object> obfs = object(
            settings, "obfs_settings", false, "protocol_settings.obfs_settings"
        );
        text(obfs, "path", false, "protocol_settings.obfs_settings.path");
        text(obfs, "host", false, "protocol_settings.obfs_settings.host");
    }

    private static void validateVmess(Map<String, Object> settings) {
        int mode = tlsMode(settings, true, Set.of(0, 1));
        validateTransport(settings);
        list(settings, "rules", false, "protocol_settings.rules");
        validateTlsSettings(settings, "tls_settings", mode == 1);
        validateUtls(settings);
        validateMultiplex(settings);
    }

    private static void validateVless(Map<String, Object> settings) {
        int mode = tlsMode(settings, true, Set.of(0, 1, 2));
        validateTransport(settings);
        text(settings, "flow", false, "protocol_settings.flow");
        Map<String, Object> encryption = object(
            settings, "encryption", false, "protocol_settings.encryption"
        );
        boolean encryptionEnabled = bool(
            encryption, "enabled", false, "protocol_settings.encryption.enabled"
        );
        text(
            encryption, "encryption", encryptionEnabled,
            "protocol_settings.encryption.encryption"
        );
        text(
            encryption, "decryption", encryptionEnabled,
            "protocol_settings.encryption.decryption"
        );
        validateTlsSettings(settings, "tls_settings", mode == 1);
        validateReality(settings, mode == 2);
        validateUtls(settings);
        validateMultiplex(settings);
    }

    private static void validateTrojan(Map<String, Object> settings) {
        int mode = tlsMode(settings, false, Set.of(0, 1, 2));
        validateTransport(settings);
        text(settings, "server_name", false, "protocol_settings.server_name");
        bool(settings, "allow_insecure", false, "protocol_settings.allow_insecure");
        validateTlsSettings(settings, "tls_settings", mode == 1);
        validateReality(settings, mode == 2);
        validateUtls(settings);
        validateMultiplex(settings);
    }

    private static void validateHysteria(Map<String, Object> settings) {
        int version = integer(
            settings, "version", true, 1, 2, "protocol_settings.version"
        );
        if (version != 1 && version != 2) {
            throw invalid("protocol_settings.version must be 1 or 2");
        }
        alpn(settings.get("alpn"), false, "protocol_settings.alpn", true);
        Map<String, Object> obfs = object(
            settings, "obfs", false, "protocol_settings.obfs"
        );
        boolean obfsEnabled = bool(
            obfs, "open", false, "protocol_settings.obfs.open"
        );
        text(obfs, "type", obfsEnabled && version == 2, "protocol_settings.obfs.type");
        text(obfs, "password", obfsEnabled, "protocol_settings.obfs.password");
        Map<String, Object> bandwidth = object(
            settings, "bandwidth", false, "protocol_settings.bandwidth"
        );
        integer(bandwidth, "up", false, 0, Integer.MAX_VALUE, "protocol_settings.bandwidth.up");
        integer(bandwidth, "down", false, 0, Integer.MAX_VALUE, "protocol_settings.bandwidth.down");
        integer(settings, "hop_interval", false, 0, Integer.MAX_VALUE, "protocol_settings.hop_interval");
        validateTlsObject(settings, "tls", false);
    }

    private static void validateTuic(Map<String, Object> settings) {
        Integer version = optionalInteger(
            settings, "version", 4, 5, "protocol_settings.version"
        );
        if (version != null && version != 4 && version != 5) {
            throw invalid("protocol_settings.version must be 4 or 5");
        }
        String congestion = text(
            settings, "congestion_control", false,
            "protocol_settings.congestion_control"
        );
        if (congestion != null && !congestion.isBlank()
            && !Set.of("bbr", "cubic", "new_reno").contains(congestion)) {
            throw invalid("protocol_settings.congestion_control is not supported");
        }
        alpn(settings.get("alpn"), false, "protocol_settings.alpn", false);
        String relay = text(
            settings, "udp_relay_mode", false, "protocol_settings.udp_relay_mode"
        );
        if (relay != null && !relay.isBlank() && !Set.of("native", "quic").contains(relay)) {
            throw invalid("protocol_settings.udp_relay_mode is not supported");
        }
        text(settings, "password", false, "protocol_settings.password");
        validateTlsObject(settings, "tls", false);
    }

    private static void validateSocks(Map<String, Object> settings) {
        Integer version = optionalInteger(
            settings, "version", 4, 5, "protocol_settings.version"
        );
        if (version != null && version != 4 && version != 5) {
            throw invalid("protocol_settings.version must be 4 or 5");
        }
        int mode = tlsMode(settings, false, Set.of(0, 1));
        validateTlsSettings(settings, "tls_settings", mode == 1);
    }

    private static void validateSimpleTls(Map<String, Object> settings, String type) {
        int mode = tlsMode(settings, true, Set.of(0, 1));
        validateTlsSettings(settings, "tls_settings", mode == 1);
        if ("naive".equals(type) && mode != 1) {
            throw invalid("Naive nodes require TLS mode 1");
        }
    }

    private static void validateMieru(Map<String, Object> settings) {
        String transport = text(
            settings, "transport", true, "protocol_settings.transport"
        ).toUpperCase(Locale.ROOT);
        if (!Set.of("TCP", "UDP").contains(transport)) {
            throw invalid("protocol_settings.transport must be TCP or UDP");
        }
        String pattern = text(
            settings, "traffic_pattern", false, "protocol_settings.traffic_pattern"
        );
        if (!isBlank(pattern)) {
            try {
                Base64.getDecoder().decode(pattern);
            } catch (IllegalArgumentException exception) {
                throw invalid("protocol_settings.traffic_pattern must be valid Base64");
            }
        }
        validateMultiplex(settings);
    }

    private static void validateAnyTls(Map<String, Object> settings) {
        validateTlsObject(settings, "tls", false);
        List<?> padding = list(
            settings, "padding_scheme", false, "protocol_settings.padding_scheme"
        );
        if (padding != null) {
            for (Object rule : padding) {
                if (!(rule instanceof String text) || text.isBlank() || text.length() > 1024) {
                    throw invalid("protocol_settings.padding_scheme must contain non-empty strings");
                }
            }
        }
    }

    private static void validateTransport(Map<String, Object> settings) {
        String network = text(
            settings, "network", true, "protocol_settings.network"
        ).toLowerCase(Locale.ROOT);
        if (!TRANSPORTS.contains(network)) {
            throw invalid("protocol_settings.network is not supported");
        }
        Map<String, Object> networkSettings = object(
            settings, "network_settings", false,
            "protocol_settings.network_settings"
        );
        text(networkSettings, "path", false, "protocol_settings.network_settings.path");
        text(networkSettings, "host", false, "protocol_settings.network_settings.host");
        text(
            networkSettings, "service_name", false,
            "protocol_settings.network_settings.service_name"
        );
        bool(
            networkSettings, "multi_mode", false,
            "protocol_settings.network_settings.multi_mode"
        );
        bool(
            networkSettings, "acceptProxyProtocol", false,
            "protocol_settings.network_settings.acceptProxyProtocol"
        );
        String mode = text(
            networkSettings, "mode", false,
            "protocol_settings.network_settings.mode"
        );
        if ("xhttp".equals(network) && mode != null && !mode.isBlank()
            && !Set.of("auto", "packet-up", "stream-up", "stream-one").contains(mode)) {
            throw invalid("protocol_settings.network_settings.mode is not supported");
        }
    }

    private static int tlsMode(
        Map<String, Object> settings,
        boolean required,
        Set<Integer> allowed
    ) {
        Integer mode = optionalInteger(
            settings, "tls", 0, 2, "protocol_settings.tls"
        );
        if (mode == null) {
            if (required) throw invalid("protocol_settings.tls is required");
            return 0;
        }
        if (!allowed.contains(mode)) {
            throw invalid("protocol_settings.tls contains an unsupported mode");
        }
        return mode;
    }

    private static void validateTlsSettings(
        Map<String, Object> settings,
        String key,
        boolean required
    ) {
        validateTlsObject(settings, key, required);
    }

    private static void validateTlsObject(
        Map<String, Object> settings,
        String key,
        boolean required
    ) {
        String path = "protocol_settings." + key;
        Map<String, Object> tls = object(settings, key, required, path);
        if (tls.isEmpty() && !settings.containsKey(key)) return;
        String serverName = text(tls, "server_name", false, path + ".server_name");
        if (serverName != null && !serverName.isBlank()) {
            validateAddress(serverName, path + ".server_name");
        }
        bool(tls, "allow_insecure", false, path + ".allow_insecure");
        alpn(tls.get("alpn"), false, path + ".alpn", false);
        validateEch(tls, path);
    }

    private static void validateEch(Map<String, Object> tls, String tlsPath) {
        String path = tlsPath + ".ech";
        Map<String, Object> ech = object(tls, "ech", false, path);
        if (ech.isEmpty() && !tls.containsKey("ech")) return;
        boolean enabled = bool(ech, "enabled", false, path + ".enabled");
        String queryName = text(
            ech, "query_server_name", false, path + ".query_server_name"
        );
        if (queryName != null && !queryName.isBlank()) {
            validateAddress(queryName, path + ".query_server_name");
        }
        String key = text(ech, "key", false, path + ".key");
        String keyPath = text(ech, "key_path", false, path + ".key_path");
        String config = text(ech, "config", false, path + ".config");
        text(ech, "config_path", false, path + ".config_path");
        if (enabled && isBlank(key) && isBlank(keyPath)) {
            throw invalid(path + " requires key or key_path when enabled");
        }
        if (!isBlank(key)) validatePem(key, "ECH KEYS", path + ".key");
        if (!isBlank(config)) validatePem(config, "ECH CONFIGS", path + ".config");
    }

    private static void validateReality(Map<String, Object> settings, boolean required) {
        String path = "protocol_settings.reality_settings";
        Map<String, Object> reality = object(settings, "reality_settings", required, path);
        if (reality.isEmpty() && !settings.containsKey("reality_settings")) return;
        bool(reality, "allow_insecure", false, path + ".allow_insecure");
        String serverName = text(reality, "server_name", false, path + ".server_name");
        String destination = text(reality, "dest", false, path + ".dest");
        if (required && isBlank(serverName) && isBlank(destination)) {
            throw invalid(path + " requires server_name or dest");
        }
        if (!isBlank(serverName)) validateAddress(serverName, path + ".server_name");
        integer(reality, "server_port", false, 1, 65_535, path + ".server_port");
        String privateKey = text(
            reality, "private_key", required, path + ".private_key"
        );
        String publicKey = text(
            reality, "public_key", false, path + ".public_key"
        );
        if (!isBlank(privateKey)) validateX25519Key(privateKey, path + ".private_key");
        if (!isBlank(publicKey)) validateX25519Key(publicKey, path + ".public_key");
        String shortId = text(reality, "short_id", false, path + ".short_id");
        if (!isBlank(shortId) && !SHORT_ID.matcher(shortId).matches()) {
            throw invalid(path + ".short_id must be an even-length hexadecimal value up to 16 characters");
        }
    }

    private static void validateUtls(Map<String, Object> settings) {
        String path = "protocol_settings.utls";
        Map<String, Object> utls = object(settings, "utls", false, path);
        if (utls.isEmpty() && !settings.containsKey("utls")) return;
        boolean enabled = bool(utls, "enabled", false, path + ".enabled");
        String fingerprint = text(
            utls, "fingerprint", enabled, path + ".fingerprint"
        );
        if (!isBlank(fingerprint) && !SAFE_TOKEN.matcher(fingerprint).matches()) {
            throw invalid(path + ".fingerprint contains invalid characters");
        }
    }

    private static void validateMultiplex(Map<String, Object> settings) {
        String path = "protocol_settings.multiplex";
        Map<String, Object> multiplex = object(settings, "multiplex", false, path);
        if (multiplex.isEmpty() && !settings.containsKey("multiplex")) return;
        boolean enabled = bool(multiplex, "enabled", false, path + ".enabled");
        String protocol = text(
            multiplex, "protocol", enabled, path + ".protocol"
        );
        if (!isBlank(protocol) && !MULTIPLEX_PROTOCOLS.contains(protocol)) {
            throw invalid(path + ".protocol is not supported");
        }
        Integer maxConnections = optionalInteger(
            multiplex, "max_connections", 0, Integer.MAX_VALUE,
            path + ".max_connections"
        );
        Integer minStreams = optionalInteger(
            multiplex, "min_streams", 0, Integer.MAX_VALUE,
            path + ".min_streams"
        );
        Integer maxStreams = optionalInteger(
            multiplex, "max_streams", 0, Integer.MAX_VALUE,
            path + ".max_streams"
        );
        if (minStreams != null && maxStreams != null && maxStreams > 0
            && minStreams > maxStreams) {
            throw invalid(path + ".min_streams must not exceed max_streams");
        }
        if (enabled && maxConnections != null && maxConnections == 0) {
            throw invalid(path + ".max_connections must be positive when multiplex is enabled");
        }
        bool(multiplex, "padding", false, path + ".padding");
        Map<String, Object> brutal = object(
            multiplex, "brutal", false, path + ".brutal"
        );
        boolean brutalEnabled = bool(
            brutal, "enabled", false, path + ".brutal.enabled"
        );
        integer(
            brutal, "up_mbps", brutalEnabled, brutalEnabled ? 1 : 0,
            Integer.MAX_VALUE, path + ".brutal.up_mbps"
        );
        integer(
            brutal, "down_mbps", brutalEnabled, brutalEnabled ? 1 : 0,
            Integer.MAX_VALUE, path + ".brutal.down_mbps"
        );
    }

    private static void alpn(
        Object value,
        boolean required,
        String path,
        boolean stringOnly
    ) {
        if (value == null) {
            if (required) throw invalid(path + " is required");
            return;
        }
        if (value instanceof String text) {
            validateAlpnToken(text, path);
            return;
        }
        if (!stringOnly && value instanceof List<?> values) {
            if (required && values.isEmpty()) throw invalid(path + " is required");
            for (Object item : values) {
                if (!(item instanceof String text)) {
                    throw invalid(path + " must contain strings");
                }
                validateAlpnToken(text, path);
            }
            return;
        }
        throw invalid(path + (stringOnly ? " must be a string" : " must be a string or string array"));
    }

    private static void validateAlpnToken(String value, String path) {
        if (value.isBlank() || value.length() > 255
            || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw invalid(path + " contains an invalid ALPN value");
        }
    }

    private static void validatePem(String value, String label, String path) {
        if (value.length() > 65_536
            || !value.startsWith("-----BEGIN " + label + "-----")
            || !value.trim().endsWith("-----END " + label + "-----")) {
            throw invalid(path + " must contain a valid " + label + " PEM block");
        }
    }

    private static void validateX25519Key(String value, String path) {
        if (!BASE64_URL_KEY.matcher(value).matches()) {
            throw invalid(path + " must be a base64url X25519 key");
        }
        try {
            if (Base64.getUrlDecoder().decode(value).length != 32) {
                throw invalid(path + " must decode to 32 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw invalid(path + " must be a base64url X25519 key");
        }
    }

    private static void validateDomain(String value, String field) {
        String candidate = value.endsWith(".")
            ? value.substring(0, value.length() - 1)
            : value;
        try {
            candidate = IDN.toASCII(candidate).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) {
            throw invalid(field + " must be a valid hostname or IP address");
        }
        if (candidate.isEmpty() || candidate.length() > 253) {
            throw invalid(field + " must be a valid hostname or IP address");
        }
        for (String label : candidate.split("\\.", -1)) {
            if (!DNS_LABEL.matcher(label).matches()) {
                throw invalid(field + " must be a valid hostname or IP address");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(
        Map<String, Object> parent,
        String key,
        boolean required,
        String path
    ) {
        Object value = parent.get(key);
        if (value == null) {
            if (required) throw invalid(path + " is required and must be an object");
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(path + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static List<?> list(
        Map<String, Object> parent,
        String key,
        boolean required,
        String path
    ) {
        Object value = parent.get(key);
        if (value == null) {
            if (required) throw invalid(path + " is required and must be an array");
            return null;
        }
        if (!(value instanceof List<?> list)) {
            throw invalid(path + " must be an array");
        }
        return list;
    }

    private static String text(
        Map<String, Object> parent,
        String key,
        boolean required,
        String path
    ) {
        Object value = parent.get(key);
        if (value == null) {
            if (required) throw invalid(path + " is required");
            return null;
        }
        if (!(value instanceof String text) || text.length() > 65_536
            || (required && text.isBlank())) {
            throw invalid(path + " must be " + (required ? "a non-empty string" : "a string"));
        }
        return text.trim();
    }

    private static boolean bool(
        Map<String, Object> parent,
        String key,
        boolean required,
        String path
    ) {
        Object value = parent.get(key);
        if (value == null) {
            if (required) throw invalid(path + " is required");
            return false;
        }
        if (!(value instanceof Boolean bool)) {
            throw invalid(path + " must be a boolean");
        }
        return bool;
    }

    private static int integer(
        Map<String, Object> parent,
        String key,
        boolean required,
        int minimum,
        int maximum,
        String path
    ) {
        Integer value = optionalInteger(parent, key, minimum, maximum, path);
        if (value == null) {
            if (required) throw invalid(path + " is required");
            return 0;
        }
        return value;
    }

    private static Integer optionalInteger(
        Map<String, Object> parent,
        String key,
        int minimum,
        int maximum,
        String path
    ) {
        Object value = parent.get(key);
        if (value == null) return null;
        if (!(value instanceof Number number)
            || !Double.isFinite(number.doubleValue())
            || number.doubleValue() != Math.rint(number.doubleValue())
            || number.longValue() < minimum
            || number.longValue() > maximum) {
            throw invalid(path + " must be an integer between " + minimum + " and " + maximum);
        }
        return number.intValue();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_NODE",
            detail
        );
    }
}
