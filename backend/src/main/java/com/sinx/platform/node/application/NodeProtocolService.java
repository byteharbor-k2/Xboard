package com.sinx.platform.node.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.NodeRouteRule;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NodeProtocolService {

    private final NodeMachineService machines;
    private final ProxyNodeRepository nodes;
    private final NodeRouteRuleRepository routes;
    private final SubscriptionEntitlementRepository entitlements;
    private final NodeTrafficRateCalculator trafficRates;
    private final NodeDeviceStateService deviceStates;
    private final PlatformConfigurationService configuration;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeProtocolService(
        NodeMachineService machines,
        ProxyNodeRepository nodes,
        NodeRouteRuleRepository routes,
        SubscriptionEntitlementRepository entitlements,
        NodeTrafficRateCalculator trafficRates,
        NodeDeviceStateService deviceStates,
        PlatformConfigurationService configuration,
        ApplicationEventPublisher eventPublisher,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.machines = machines;
        this.nodes = nodes;
        this.routes = routes;
        this.entitlements = entitlements;
        this.trafficRates = trafficRates;
        this.deviceStates = deviceStates;
        this.configuration = configuration;
        this.eventPublisher = eventPublisher;
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

    public void authenticateLegacyToken(String token) {
        String expected = configuration.nodeCommunicationSettings().legacyToken();
        if (token == null || token.isBlank() || expected == null || expected.isBlank()
            || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
            )) {
            throw forbidden("Invalid legacy server token");
        }
    }

    public AuthenticatedNode authenticateLegacy(long nodeId, String token) {
        authenticateLegacyToken(token);
        ProxyNode node = nodes.findFirstByCode(Long.toString(nodeId))
            .or(() -> nodes.findById(nodeId))
            .orElseThrow(() -> forbidden("Node not found"));
        return new AuthenticatedNode(null, node);
    }

    public ConfigPayload config(long machineId, long nodeId, String token) {
        ProxyNode node = authenticate(machineId, nodeId, token).node();
        return config(node);
    }

    public ConfigPayload configLegacy(long nodeId, String token) {
        return config(authenticateLegacy(nodeId, token).node());
    }

    private ConfigPayload config(ProxyNode node) {
        Map<String, Object> settings = jsonMap(node.getProtocolSettings());
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("node_id", node.getId());
        config.put("protocol", node.getType());
        config.put("listen_ip", settings.getOrDefault("listen_ip", "0.0.0.0"));
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
        PlatformConfigurationService.NodeCommunicationSettings communication =
            configuration.nodeCommunicationSettings();
        config.put("base_config", Map.of(
            "push_interval", communication.pushIntervalSeconds(),
            "pull_interval", communication.pullIntervalSeconds()
        ));
        config.put("routes", routePayload(node));
        putJson(config, "custom_outbounds", node.getCustomOutbounds(), true);
        putJson(config, "custom_routes", node.getCustomRoutes(), true);
        putCertificateConfig(config, node.getCertConfig());
        return new ConfigPayload(config, etag(config));
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
        return users(node);
    }

    public UsersPayload usersLegacy(long nodeId, String token) {
        return users(authenticateLegacy(nodeId, token).node());
    }

    public Map<Long, Integer> aliveListLegacy(long nodeId, String token) {
        ProxyNode node = authenticateLegacy(nodeId, token).node();
        Set<Long> limitedUserIds = new java.util.LinkedHashSet<>();
        for (Map<String, Object> user : users(node).users()) {
            if (number(user.get("device_limit")) <= 0) continue;
            Long nodeUserId = positiveLong(user.get("id"));
            if (nodeUserId != null) limitedUserIds.add(nodeUserId);
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        deviceStates.snapshotForUsers(limitedUserIds, clock.instant())
            .forEach((userId, addresses) -> {
                if (addresses != null && !addresses.isEmpty()) {
                    counts.put(userId, addresses.size());
                }
            });
        return counts;
    }

    private UsersPayload users(ProxyNode node) {
        Set<Long> groupIds = new HashSet<>(jsonLongList(node.getGroupIds()));
        if (groupIds.isEmpty()) {
            return new UsersPayload(List.of(), etag(List.of()));
        }
        var now = clock.instant();
        List<Map<String, Object>> users = entitlements.findAllWithUserAndPlan().stream()
            .filter(entitlement -> entitlement.stateAt(now) == EntitlementState.ACTIVE)
            .filter(entitlement -> eligible(entitlement, groupIds))
            .sorted(Comparator.comparing(entitlement -> entitlement.getUser().getNodeUserId()))
            .map(this::userPayload)
            .toList();
        return new UsersPayload(users, etag(users));
    }

    @Transactional
    public void report(long machineId, long nodeId, String token, Map<String, Object> payload) {
        ProxyNode node = authenticate(machineId, nodeId, token).node();
        report(node, payload);
    }

    @Transactional
    public void reportLegacy(long nodeId, String token, Map<String, Object> payload) {
        report(authenticateLegacy(nodeId, token).node(), payload);
    }

    private void report(ProxyNode node, Map<String, Object> payload) {
        Set<Long> groupIds = new HashSet<>(jsonLongList(node.getGroupIds()));
        Set<Long> exhaustedGroupIds = new LinkedHashSet<>();
        var now = clock.instant();
        BigDecimal currentRate = trafficRates.currentRate(node, now);
        long upload = 0;
        long download = 0;
        int reportedUsers = node.getOnlineUsers();
        Object trafficValue = payload.get("traffic");
        if (trafficValue instanceof Map<?, ?> traffic) {
            reportedUsers = 0;
            for (Map.Entry<?, ?> entry : traffic.entrySet()) {
                Long nodeUserId = positiveLong(entry.getKey());
                Object value = entry.getValue();
                if (value instanceof List<?> pair && pair.size() >= 2) {
                    long uploadedDelta = number(pair.get(0));
                    long downloadedDelta = number(pair.get(1));
                    if (nodeUserId == null || (uploadedDelta == 0 && downloadedDelta == 0)) continue;
                    reportedUsers++;
                    // Node counters describe everything the node actually handled. User
                    // entitlement counters are intentionally stricter and are only charged
                    // when the reported user is still eligible for this node.
                    upload = saturatedAdd(upload, uploadedDelta);
                    download = saturatedAdd(download, downloadedDelta);
                    SubscriptionEntitlement entitlement = entitlements.findForTrafficReport(nodeUserId)
                        .orElse(null);
                    if (entitlement == null
                        || entitlement.stateAt(now) != EntitlementState.ACTIVE
                        || !eligible(entitlement, groupIds)) {
                        continue;
                    }
                    entitlement.addUsage(
                        trafficRates.charge(uploadedDelta, currentRate),
                        trafficRates.charge(downloadedDelta, currentRate),
                        now
                    );
                    if (entitlement.stateAt(now) == EntitlementState.EXHAUSTED) {
                        exhaustedGroupIds.add(
                            entitlement.getEffectiveServerGroupId()
                        );
                    }
                }
            }
        }
        if (payload.containsKey("alive") && payload.get("alive") instanceof Map<?, ?> alive) {
            deviceStates.replaceSnapshot(node.getId(), deviceSnapshot(alive), now);
        }
        int onlineConnections = node.getOnlineConnections();
        if (payload.get("online") instanceof Map<?, ?> online) {
            onlineConnections = 0;
            for (Object value : online.values()) onlineConnections += (int) number(value);
        }
        String loadStatus = payload.containsKey("status")
            ? jsonNullable(payload.get("status"))
            : node.getLoadStatus();
        String metrics = payload.containsKey("metrics")
            ? jsonNullable(payload.get("metrics"))
            : node.getMetrics();
        node.recordReport(
            upload, download, reportedUsers, onlineConnections,
            loadStatus, metrics, now
        );
        if (!exhaustedGroupIds.isEmpty()) {
            eventPublisher.publishEvent(NodeAccessGroupsChangedEvent.of(
                exhaustedGroupIds,
                "subscription traffic exhausted"
            ));
        }
    }

    private Map<Long, List<String>> deviceSnapshot(Map<?, ?> rawSnapshot) {
        Map<Long, List<String>> snapshot = new LinkedHashMap<>();
        rawSnapshot.forEach((rawUserId, rawAddresses) -> {
            Long nodeUserId = positiveLong(rawUserId);
            if (nodeUserId == null || !(rawAddresses instanceof List<?> values)) return;
            List<String> addresses = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
            snapshot.put(nodeUserId, addresses);
        });
        return snapshot;
    }

    private List<Map<String, Object>> routePayload(ProxyNode node) {
        List<Long> routeIds = jsonLongList(node.getRouteIds());
        if (routeIds.isEmpty()) return List.of();
        Map<Long, NodeRouteRule> byId = new HashMap<>();
        routes.findAllById(routeIds).forEach(route -> byId.put(route.getId(), route));
        return routeIds.stream().distinct().map(byId::get).filter(java.util.Objects::nonNull)
            .map(route -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", route.getId());
                value.put("match", jsonStringList(route.getMatchRules()));
                value.put("action", route.getAction());
                if (route.getActionValue() != null && !route.getActionValue().isBlank()) {
                    value.put("action_value", route.getActionValue());
                }
                return value;
            }).toList();
    }

    private Map<String, Object> userPayload(SubscriptionEntitlement entitlement) {
        UserAccount user = entitlement.getUser();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", user.getNodeUserId());
        value.put("uuid", user.getId().toString());
        value.put("speed_limit", entitlement.getSpeedLimitMbps() == null ? 0 : entitlement.getSpeedLimitMbps());
        value.put("device_limit", entitlement.getDeviceLimit() == null ? 0 : entitlement.getDeviceLimit());
        return value;
    }

    private boolean eligible(SubscriptionEntitlement entitlement, Set<Long> groupIds) {
        UserAccount user = entitlement.getUser();
        Long effectiveGroupId = entitlement.getEffectiveServerGroupId();
        return user.getStatus() == UserStatus.ACTIVE
            && user.getNodeUserId() != null
            && effectiveGroupId != null
            && groupIds.contains(effectiveGroupId);
    }

    private void putCertificateConfig(Map<String, Object> target, String encoded) {
        if (encoded == null) return;
        Object decoded = jsonValue(encoded);
        if (!(decoded instanceof Map<?, ?> raw)) return;
        Map<String, Object> cert = new LinkedHashMap<>();
        raw.forEach((key, value) -> cert.put(String.valueOf(key), value));
        if (!cert.containsKey("cert_mode") && cert.containsKey("mode")) {
            cert.put("cert_mode", cert.remove("mode"));
        }
        if ("none".equals(cert.get("cert_mode"))) return;
        target.put("cert_config", cert);
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

    private List<Long> jsonLongList(String value) {
        Object decoded = jsonValue(value);
        if (!(decoded instanceof List<?> values)) return List.of();
        return values.stream().map(this::positiveLong).filter(java.util.Objects::nonNull).toList();
    }

    private List<String> jsonStringList(String value) {
        Object decoded = jsonValue(value);
        if (!(decoded instanceof List<?> values)) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }

    private String jsonNullable(Object value) {
        if (value == null) return null;
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { return null; }
    }

    private long number(Object value) { return value instanceof Number number ? Math.max(number.longValue(), 0) : 0; }

    private Long positiveLong(Object value) {
        try {
            long parsed = value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private String etag(Object payload) {
        try {
            byte[] source = payload instanceof String string
                ? string.getBytes(StandardCharsets.UTF_8)
                : objectMapper.writeValueAsBytes(payload);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(source);
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
