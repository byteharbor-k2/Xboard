package com.sinx.platform.subscription.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sinx.platform.node.domain.ProxyNode;

import tools.jackson.databind.ObjectMapper;

/**
 * How a stored node becomes the thing a client is handed - in particular the
 * one protocol whose credential is not the account identity.
 */
class NodeClientViewTest {

    private static final String IDENTITY = "11111111-2222-3333-4444-555555555555";
    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ProxyNode node(String type, String host, Integer port, String settings) {
        ProxyNode node = ProxyNode.create(NOW);
        node.configure(
            type, "hk-01", null, null, "[]", "[]", "香港 01", BigDecimal.ONE,
            false, "[]", 0L, "[\"hk\"]", host, port, 443, settings,
            "[]", "[]", "{}", true, true, 0, NOW
        );
        return node;
    }

    private static NodeClientView view(ProxyNode node) {
        return NodeClientView.of(node, MAPPER, IDENTITY);
    }

    @Test
    void anOrdinaryNodeCarriesTheAccountIdentity() {
        NodeClientView view = view(node(
            "vmess", "v.example.com", 443, "{\"network\":\"ws\"}"
        ));

        assertThat(view.name()).isEqualTo("香港 01");
        assertThat(view.tags()).containsExactly("hk");
        assertThat(view.protocol()).isEqualTo("vmess");
        assertThat(view.host()).isEqualTo("v.example.com");
        assertThat(view.port()).isEqualTo(443);
        assertThat(view.credential()).isEqualTo(IDENTITY);
    }

    /**
     * Shadowsocks 2022 splits the password between the node and the account. The
     * client has to present the two joined, and the account's half is the first
     * 16 or 32 characters of its identity depending on the cipher.
     */
    @Test
    void aShadowsocks2022NodeJoinsTheTwoHalvesOfItsPassword() {
        NodeClientView view = view(node(
            "shadowsocks", "hk2.example.com", 8444,
            """
            {"cipher":"2022-blake3-aes-128-gcm","server_key":"DJ8GmZ7f6kQ8hQvPQ2Z8rA"}
            """
        ));

        assertThat(view.credential())
            .isEqualTo("DJ8GmZ7f6kQ8hQvPQ2Z8rA:MTExMTExMTEtMjIyMi0zMw==");
    }

    @Test
    void theWiderCipherTakesTheLongerHalfOfTheIdentity() {
        NodeClientView view = view(node(
            "shadowsocks", "hk3.example.com", 8445,
            """
            {"cipher":"2022-blake3-aes-256-gcm","server_key":"DJ8GmZ7f6kQ8hQvPQ2Z8rA"}
            """
        ));

        assertThat(view.credential())
            .isEqualTo("DJ8GmZ7f6kQ8hQvPQ2Z8rA:MTExMTExMTEtMjIyMi0zMzMzLTQ0NDQtNTU1NTU1NTU=");
    }

    @Test
    void aShadowsocksNodeWithoutAServerKeyKeepsTheIdentity() {
        NodeClientView plain = view(node(
            "shadowsocks", "hk4.example.com", 8446,
            "{\"cipher\":\"aes-256-gcm\"}"
        ));
        NodeClientView orphan = view(node(
            "shadowsocks", "hk5.example.com", 8447,
            "{\"cipher\":\"2022-blake3-aes-128-gcm\"}"
        ));

        assertThat(plain.credential()).isEqualTo(IDENTITY);
        assertThat(orphan.credential()).isEqualTo(IDENTITY);
    }

    /** An identity shorter than the half it has to donate is used whole. */
    @Test
    void aShortIdentityIsNotPadded() {
        ProxyNode node = node(
            "shadowsocks", "hk6.example.com", 8448,
            """
            {"cipher":"2022-blake3-aes-128-gcm","server_key":"DJ8GmZ7f6kQ8hQvPQ2Z8rA"}
            """
        );

        assertThat(NodeClientView.of(node, MAPPER, "abc").credential())
            .isEqualTo("DJ8GmZ7f6kQ8hQvPQ2Z8rA:YWJj");
    }

    @Test
    void theNodePortWinsAndTheServerPortStandsInForAMissingOne() {
        ProxyNode withClientPort = node("vmess", "v.example.com", 8443, "{}");
        ProxyNode without = node("vmess", "v.example.com", null, "{}");
        ProxyNode zero = node("vmess", "v.example.com", 0, "{}");
        ProxyNode outOfRange = node("vmess", "v.example.com", 70_000, "{}");

        assertThat(view(withClientPort).port()).isEqualTo(8443);
        assertThat(view(without).port()).isEqualTo(443);
        assertThat(view(zero).port()).isEqualTo(443);
        assertThat(view(outOfRange)).isNull();
    }

    @Test
    void aNodeWithNoAddressIsNotAConfigEntry() {
        assertThat(view(node("vmess", null, 443, "{}"))).isNull();
        assertThat(view(node("vmess", "  ", 443, "{}"))).isNull();
    }

    @Test
    void unreadableSettingsAndTagsAreTolerated() {
        NodeClientView view = view(node("vmess", "v.example.com", 443, "not json"));

        assertThat(view.settings().text("network", "tcp")).isEqualTo("tcp");
        assertThat(view.tags()).containsExactly("hk");
    }
}
