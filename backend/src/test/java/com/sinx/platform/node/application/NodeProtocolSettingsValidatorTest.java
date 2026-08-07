package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.shared.web.ApiProblemException;

class NodeProtocolSettingsValidatorTest {

    @Test
    void acceptsACompleteRealityUtlsAndMultiplexConfiguration() {
        String key = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);

        assertThatCode(() -> NodeProtocolSettingsValidator.validate("vless", Map.of(
            "listen_ip", "::",
            "tls", 2,
            "network", "xhttp",
            "network_settings", Map.of(
                "path", "/gateway",
                "host", "cdn.example.test",
                "mode", "packet-up"
            ),
            "reality_settings", Map.of(
                "dest", "origin.example.test",
                "server_port", 443,
                "private_key", key,
                "public_key", key,
                "short_id", "a1b2c3d4"
            ),
            "utls", Map.of("enabled", true, "fingerprint", "chrome"),
            "multiplex", Map.of(
                "enabled", true,
                "protocol", "smux",
                "max_connections", 4,
                "min_streams", 4,
                "max_streams", 16,
                "padding", true,
                "brutal", Map.of(
                    "enabled", true,
                    "up_mbps", 100,
                    "down_mbps", 200
                )
            )
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsMalformedListenAddressesAndTransportSettings() {
        assertInvalid("vmess", Map.of(
            "listen_ip", "https://127.0.0.1",
            "tls", 0,
            "network", "tcp"
        ), "listen_ip");

        assertInvalid("vmess", Map.of(
            "tls", 0,
            "network", "xhttp",
            "network_settings", Map.of("mode", "unsupported")
        ), "network_settings.mode");

        assertInvalid("vmess", Map.of(
            "tls", 0,
            "network", "ws",
            "network_settings", "not-an-object"
        ), "network_settings");
    }

    @Test
    void rejectsIncompleteOrMalformedEchSettings() {
        assertInvalid("vmess", Map.of(
            "tls", 1,
            "network", "tcp",
            "tls_settings", Map.of(
                "ech", Map.of("enabled", true)
            )
        ), "requires key or key_path");

        assertInvalid("vmess", Map.of(
            "tls", 1,
            "network", "tcp",
            "tls_settings", Map.of(
                "ech", Map.of(
                    "enabled", true,
                    "key", "not-pem"
                )
            )
        ), "ECH KEYS PEM");

        assertThatCode(() -> NodeProtocolSettingsValidator.validate("vmess", Map.of(
            "tls", 1,
            "network", "tcp",
            "tls_settings", Map.of(
                "server_name", "edge.example.test",
                "alpn", List.of("h2", "http/1.1"),
                "ech", Map.of(
                    "enabled", true,
                    "query_server_name", "ech.example.test",
                    "key", "-----BEGIN ECH KEYS-----\nAA==\n-----END ECH KEYS-----",
                    "config", "-----BEGIN ECH CONFIGS-----\nAA==\n-----END ECH CONFIGS-----"
                )
            )
        ))).doesNotThrowAnyException();
    }

    @Test
    void rejectsInvalidRealityKeysAndShortIds() {
        assertInvalid("vless", Map.of(
            "tls", 2,
            "network", "tcp",
            "reality_settings", Map.of(
                "server_name", "origin.example.test",
                "private_key", "too-short",
                "short_id", "xyz"
            )
        ), "private_key");

        String key = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(new byte[32]);
        assertInvalid("trojan", Map.of(
            "tls", 2,
            "network", "tcp",
            "reality_settings", Map.of("private_key", key)
        ), "server_name or dest");
    }

    @Test
    void rejectsInvalidUtlsAlpnAndMultiplexValues() {
        assertInvalid("vmess", Map.of(
            "tls", 0,
            "network", "tcp",
            "utls", Map.of("enabled", true, "fingerprint", " ")
        ), "utls.fingerprint");

        assertInvalid("tuic", Map.of(
            "version", 5,
            "alpn", List.of("h3", 3)
        ), "must contain strings");

        assertInvalid("vmess", Map.of(
            "tls", 0,
            "network", "tcp",
            "multiplex", Map.of(
                "enabled", true,
                "protocol", "smux",
                "min_streams", 8,
                "max_streams", 4
            )
        ), "min_streams");

        assertInvalid("vmess", Map.of(
            "tls", 0,
            "network", "tcp",
            "multiplex", Map.of(
                "enabled", true,
                "protocol", "smux",
                "brutal", Map.of("enabled", true)
            )
        ), "brutal.up_mbps");
    }

    @Test
    void permitsAnEmptyOptionalMieruTrafficPatternButValidatesProvidedData() {
        assertThatCode(() -> NodeProtocolSettingsValidator.validate("mieru", Map.of(
            "transport", "TCP",
            "traffic_pattern", ""
        ))).doesNotThrowAnyException();

        assertInvalid("mieru", Map.of(
            "transport", "TCP",
            "traffic_pattern", "%%%"
        ), "valid Base64");
    }

    private void assertInvalid(
        String type,
        Map<String, Object> settings,
        String expectedDetail
    ) {
        assertThatThrownBy(() -> NodeProtocolSettingsValidator.validate(type, settings))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining(expectedDetail)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("INVALID_NODE");
    }
}
