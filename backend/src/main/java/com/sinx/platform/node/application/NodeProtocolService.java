package com.sinx.platform.node.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NodeProtocolService {

    private final NodeMachineService machines;
    private final ProxyNodeRepository nodes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeProtocolService(NodeMachineService machines, ProxyNodeRepository nodes,
                               ObjectMapper objectMapper, Clock clock) {
        this.machines = machines;
        this.nodes = nodes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public AuthenticatedNode authenticate(long machineId, long nodeId, String token) {
        NodeMachine machine = machines.authenticate(machineId, token);
        ProxyNode node = nodes.findById(nodeId).orElseThrow(() -> forbidden("Node not found"));
        if (node.getMachine() == null || !node.getMachine().getId().equals(machine.getId())) {
            throw forbidden("Node does not belong to this machine");
        }
        if (!node.isEnabled()) throw forbidden("Node is disabled");
        return new AuthenticatedNode(machine, node);
    }

    public ConfigPayload config(long machineId, long nodeId, String token) {
        ProxyNode node = authenticate(machineId, nodeId, token).node();
        Map<String, Object> settings = jsonMap(node.getProtocolSettings());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("node_id", node.getId());
        config.put("protocol", node.getType());
        config.put("listen_ip", "0.0.0.0");
        config.put("server_port", node.getServerPort());
        config.put("network", settings.getOrDefault("network", "tcp"));
        Object networkSettings = settings.containsKey("networkSettings")
            ? settings.get("networkSettings") : settings.getOrDefault("network_settings", Map.of());
        config.put("networkSettings", networkSettings);
        settings.forEach((key, value) -> {
            if (!"network".equals(key) && !"networkSettings".equals(key) && !"network_settings".equals(key)) {
                config.put(key, value);
            }
        });
        applyProtocolMapping(config, node, settings);
        config.put("base_config", Map.of("push_interval", 60, "pull_interval", 60));
        config.put("routes", List.of());
        putJson(config, "custom_outbounds", node.getCustomOutbounds(), true);
        putJson(config, "custom_routes", node.getCustomRoutes(), true);
        putJson(config, "cert_config", node.getCertConfig(), false);
        return new ConfigPayload(config, etag(node.getUpdatedAt().toEpochMilli() + ":config"));
    }

    private void applyProtocolMapping(Map<String, Object> config, ProxyNode node, Map<String, Object> settings) {
        switch (node.getType()) {
            case "shadowsocks" -> {
                copy(config, settings, "cipher", "plugin", "plugin_opts", "server_key");
            }
            case "vmess" -> {
                copy(config, settings, "tls", "tls_settings", "multiplex");
            }
            case "trojan" -> {
                config.put("host", node.getHost());
                config.put("server_name", nested(settings, "tls_settings", "server_name"));
                copy(config, settings, "tls", "multiplex");
                config.put("tls_settings", integer(settings.get("tls")) == 2
                    ? settings.get("reality_settings") : settings.get("tls_settings"));
            }
            case "vless" -> {
                copy(config, settings, "tls", "flow", "multiplex");
                Object encryptionEnabled = nested(settings, "encryption", "enabled");
                config.put("decryption", Boolean.TRUE.equals(encryptionEnabled)
                    ? nested(settings, "encryption", "decryption") : null);
                config.put("tls_settings", integer(settings.get("tls")) == 2
                    ? settings.get("reality_settings") : settings.get("tls_settings"));
            }
            case "hysteria" -> {
                config.put("host", node.getHost());
                copy(config, settings, "version");
                config.put("server_name", nested(settings, "tls", "server_name"));
                config.put("tls_settings", settings.get("tls"));
                config.put("up_mbps", integer(nested(settings, "bandwidth", "up")));
                config.put("down_mbps", integer(nested(settings, "bandwidth", "down")));
                if (integer(settings.get("version")) == 1) {
                    config.put("obfs", nested(settings, "obfs", "password"));
                } else {
                    config.put("obfs", Boolean.TRUE.equals(nested(settings, "obfs", "open"))
                        ? nested(settings, "obfs", "type") : null);
                    config.put("obfs-password", nested(settings, "obfs", "password"));
                }
            }
            case "tuic" -> {
                copy(config, settings, "version", "congestion_control");
                config.put("server_name", nested(settings, "tls", "server_name"));
                config.put("tls_settings", settings.get("tls"));
                config.put("auth_timeout", "3s");
                config.put("zero_rtt_handshake", false);
                config.put("heartbeat", "3s");
            }
            case "anytls" -> {
                config.put("server_name", nested(settings, "tls", "server_name"));
                config.put("tls_settings", settings.get("tls"));
                copy(config, settings, "padding_scheme");
            }
            case "socks", "naive", "http" -> copy(config, settings, "tls", "tls_settings");
            case "mieru" -> copy(config, settings, "transport", "traffic_pattern", "multiplex");
            default -> { }
        }
    }

    private void copy(Map<String, Object> target, Map<String, Object> source, String... keys) {
        for (String key : keys) if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private Object nested(Map<String, Object> source, String parent, String child) {
        Object value = source.get(parent);
        return value instanceof Map<?, ?> map ? map.get(child) : null;
    }

    private int integer(Object value) { return value instanceof Number number ? number.intValue() : 0; }

    public UsersPayload users(long machineId, long nodeId, String token) {
        ProxyNode node = authenticate(machineId, nodeId, token).node();
        // Permission-group membership is populated in phase 3. An empty list is
        // safer than granting access before the Xboard group semantics exist.
        List<Map<String, Object>> users = List.of();
        return new UsersPayload(users, etag(node.getUpdatedAt().toEpochMilli() + ":users"));
    }

    @Transactional
    public void report(long machineId, long nodeId, String token, Map<String, Object> payload) {
        ProxyNode node = authenticate(machineId, nodeId, token).node();
        long upload = 0;
        long download = 0;
        Object trafficValue = payload.get("traffic");
        if (trafficValue instanceof Map<?, ?> traffic) {
            for (Object value : traffic.values()) {
                if (value instanceof List<?> pair && pair.size() >= 2) {
                    upload += number(pair.get(0));
                    download += number(pair.get(1));
                }
            }
        }
        int onlineUsers = payload.get("alive") instanceof Map<?, ?> alive ? alive.size() : 0;
        int onlineConnections = 0;
        if (payload.get("online") instanceof Map<?, ?> online) {
            for (Object value : online.values()) onlineConnections += (int) number(value);
        }
        node.recordReport(
            upload, download, onlineUsers, onlineConnections,
            jsonNullable(payload.get("status")), jsonNullable(payload.get("metrics")), clock.instant()
        );
    }

    private void putJson(Map<String, Object> target, String key, String value, boolean includeEmpty) {
        if (value == null) return;
        Object decoded = jsonValue(value);
        if (includeEmpty || decoded != null) target.put(key, decoded);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String value) {
        try { return objectMapper.readValue(value, Map.class); }
        catch (Exception exception) { return Map.of(); }
    }

    private Object jsonValue(String value) {
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception exception) { return null; }
    }

    private String jsonNullable(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { return null; }
    }

    private long number(Object value) { return value instanceof Number number ? Math.max(number.longValue(), 0) : 0; }

    private String etag(String seed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (Exception exception) {
            throw new IllegalStateException("Could not calculate node ETag", exception);
        }
    }

    private ApiProblemException forbidden(String detail) {
        return new ApiProblemException(HttpStatus.FORBIDDEN, "INVALID_NODE_CREDENTIALS", detail);
    }

    public record AuthenticatedNode(NodeMachine machine, ProxyNode node) {}
    public record ConfigPayload(Map<String, Object> data, String etag) {}
    public record UsersPayload(List<Map<String, Object>> users, String etag) {}
}
