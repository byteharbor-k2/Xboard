package com.sinx.platform.subscription.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sinx.platform.node.domain.ProxyNode;

import tools.jackson.databind.ObjectMapper;

/**
 * One node as a client needs to see it.
 *
 * Built once per request and handed to whichever renderer the client asked
 * for, so the three of them agree on what a node's address and credential are.
 * Each of them then reads {@link #settings()} for the parts that belong to its
 * own format.
 */
public record NodeClientView(
    String name,
    List<String> tags,
    String protocol,
    String host,
    int port,
    String credential,
    SettingsView settings
) {

    private static final Set<String> SHADOWSOCKS_2022 = Set.of(
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305"
    );

    /**
     * The node as this account sees it, or null when it cannot be expressed on
     * the wire at all.
     *
     * A node with no address is not a node a client can connect to, and it
     * would otherwise be rendered as a proxy pointing at "null".
     */
    public static NodeClientView of(ProxyNode node, ObjectMapper mapper, String clientIdentity) {
        if (node.getHost() == null || node.getHost().isBlank()) {
            return null;
        }
        Integer clientPort = node.getPort();
        int port = clientPort == null || clientPort <= 0
            ? node.getServerPort()
            : clientPort;
        if (port <= 0 || port > 65_535) {
            return null;
        }
        SettingsView settings = SettingsView.of(
            JsonMaps.parseObject(node.getProtocolSettings(), mapper)
        );
        return new NodeClientView(
            node.getName(),
            tagsOf(node, mapper),
            node.getType(),
            node.getHost(),
            port,
            credentialFor(node.getType(), settings, clientIdentity),
            settings
        );
    }

    private static List<String> tagsOf(ProxyNode node, ObjectMapper mapper) {
        String json = node.getTags();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            if (!(mapper.readValue(json, List.class) instanceof List<?> parsed)) {
                return List.of();
            }
            return parsed.stream().map(String::valueOf).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    /**
     * What the client authenticates with.
     *
     * Almost always the account identity itself. Shadowsocks is the exception:
     * since 2022 the node holds one half of the password and the account holds
     * the other, and the client has to present them joined or the node will
     * reject it. The node was configured with the half this panel generated, so
     * the client has to be handed the same join.
     */
    private static String credentialFor(
        String protocol,
        SettingsView settings,
        String clientIdentity
    ) {
        if (!"shadowsocks".equals(protocol)) {
            return clientIdentity;
        }
        String cipher = settings.text("cipher");
        String serverKey = settings.text("server_key");
        if (serverKey == null || cipher == null || !SHADOWSOCKS_2022.contains(cipher)) {
            return clientIdentity;
        }
        int size = "2022-blake3-aes-128-gcm".equals(cipher) ? 16 : 32;
        byte[] key = clientIdentity.getBytes(StandardCharsets.UTF_8);
        byte[] userKey = key.length <= size ? key : java.util.Arrays.copyOf(key, size);
        return serverKey + ":" + Base64.getEncoder().encodeToString(userKey);
    }
}
