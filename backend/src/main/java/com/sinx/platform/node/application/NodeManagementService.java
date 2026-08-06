package com.sinx.platform.node.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.security.KeyPairGenerator;
import java.security.interfaces.XECPrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Base64;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeMachineRepository;
import com.sinx.platform.node.repository.NodeAccessGroupRepository;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NodeManagementService {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final Set<String> SUPPORTED_PROTOCOLS = Set.of(
        "hysteria", "vless", "trojan", "vmess", "tuic", "shadowsocks",
        "anytls", "socks", "naive", "http", "mieru"
    );

    private final ProxyNodeRepository nodes;
    private final NodeMachineRepository machines;
    private final NodeAccessGroupRepository groups;
    private final NodeRouteRuleRepository routes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeManagementService(
        ProxyNodeRepository nodes,
        NodeMachineRepository machines,
        NodeAccessGroupRepository groups,
        NodeRouteRuleRepository routes,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.nodes = nodes;
        this.machines = machines;
        this.groups = groups;
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<NodeView> list() {
        return nodes.findAllByOrderBySortOrderAscIdAsc().stream().map(this::view).toList();
    }

    public List<NodeView> forMachine(long machineId, boolean enabledOnly) {
        List<ProxyNode> result = enabledOnly
            ? nodes.findByMachineIdAndEnabledTrueOrderBySortOrderAscIdAsc(machineId)
            : nodes.findByMachineIdOrderBySortOrderAscIdAsc(machineId);
        return result.stream().map(this::view).toList();
    }

    @Transactional
    public NodeView save(NodeDraft draft) {
        ProxyNode node = draft.id() == null
            ? ProxyNode.create(clock.instant())
            : requireNode(draft.id());
        configure(node, draft, draft.id() == null ? nextSort() : node.getSortOrder());
        return view(nodes.save(node));
    }

    @Transactional
    public void quickUpdate(long id, Boolean show, Boolean enabled, Long machineId, boolean updateMachine) {
        ProxyNode node = requireNode(id);
        NodeMachine updatedMachine = updateMachine ? machine(machineId) : null;
        node.quickUpdate(show, enabled, updatedMachine, updateMachine, clock.instant());
    }

    @Transactional
    public void delete(long id) {
        nodes.delete(requireNode(id));
    }

    @Transactional
    public NodeView copy(long id) {
        ProxyNode source = requireNode(id);
        NodeDraft draft = new NodeDraft(
            null, source.getType(), null, source.getParentId(),
            source.getMachine() == null ? null : source.getMachine().getId(),
            jsonList(source.getGroupIds()), jsonList(source.getRouteIds()),
            source.getName() + " copy", source.getRate(), source.isRateTimeEnable(),
            jsonValue(source.getRateTimeRanges()), source.getTransferEnable(),
            jsonStringList(source.getTags()), source.getHost(), source.getPort(),
            source.getServerPort(), jsonMap(source.getProtocolSettings()),
            jsonValue(source.getCustomOutbounds()), jsonValue(source.getCustomRoutes()),
            nullableJsonValue(source.getCertConfig()), false, source.isEnabled(), null
        );
        return save(draft);
    }

    @Transactional
    public void sort(List<SortItem> items) {
        Instant now = clock.instant();
        for (SortItem item : items) {
            requireNode(item.id()).changeSort(item.order(), now);
        }
    }

    @Transactional
    public void batchDelete(List<Long> ids) {
        ids.stream().map(this::requireNode).forEach(nodes::delete);
    }

    @Transactional
    public void batchUpdate(List<Long> ids, Boolean show, Boolean enabled, Long machineId, boolean updateMachine) {
        ids.forEach(id -> quickUpdate(id, show, enabled, machineId, updateMachine));
    }

    @Transactional
    public void resetTraffic(List<Long> ids) {
        Instant now = clock.instant();
        ids.stream().map(this::requireNode).forEach(node -> node.resetTraffic(now));
    }

    public EchKeyPair generateEchKey(String publicName) {
        String domain = publicName == null ? "ech.example.com" : publicName.trim();
        if (domain.isEmpty() || domain.length() > 253 || !domain.matches("(?i)[a-z0-9.-]+")) {
            throw invalid("ECH public name must be a valid domain between 1 and 253 characters");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
            generator.initialize(NamedParameterSpec.X25519);
            var pair = generator.generateKeyPair();
            byte[] privateKey = ((XECPrivateKey) pair.getPrivate()).getScalar().orElseThrow();
            byte[] publicKey = littleEndian(((XECPublicKey) pair.getPublic()).getU(), 32);
            int configId = RANDOM.nextInt(256);

            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            writeU8(contents, configId);
            writeU16(contents, 0x0020);
            writeU16(contents, publicKey.length);
            contents.writeBytes(publicKey);
            writeU16(contents, 8);
            writeU16(contents, 0x0001);
            writeU16(contents, 0x0001);
            writeU16(contents, 0x0001);
            writeU16(contents, 0x0003);
            writeU8(contents, 0);
            byte[] publicNameBytes = domain.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            writeU8(contents, publicNameBytes.length);
            contents.writeBytes(publicNameBytes);
            writeU16(contents, 0);

            ByteArrayOutputStream config = new ByteArrayOutputStream();
            writeU16(config, 0xfe0d);
            writeU16(config, contents.size());
            config.writeBytes(contents.toByteArray());

            ByteArrayOutputStream configList = new ByteArrayOutputStream();
            writeU16(configList, config.size());
            configList.writeBytes(config.toByteArray());

            ByteArrayOutputStream keys = new ByteArrayOutputStream();
            writeU16(keys, privateKey.length);
            keys.writeBytes(privateKey);
            writeU16(keys, config.size());
            keys.writeBytes(config.toByteArray());

            return new EchKeyPair(
                pem("ECH KEYS", keys.toByteArray()),
                pem("ECH CONFIGS", configList.toByteArray())
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Could not generate ECH key material", exception);
        }
    }

    public ProxyNode requireNode(long id) {
        return nodes.findById(id).orElseThrow(() -> new ApiProblemException(
            HttpStatus.NOT_FOUND, "NODE_NOT_FOUND", "Node does not exist"
        ));
    }

    private void configure(ProxyNode node, NodeDraft draft, int defaultSort) {
        String type = draft.type() == null ? "" : draft.type().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROTOCOLS.contains(type)) {
            throw invalid("Unsupported node protocol");
        }
        if (draft.name() == null || draft.name().isBlank() || draft.name().trim().length() > 255) {
            throw invalid("Node name is required and must not exceed 255 characters");
        }
        if (draft.serverPort() == null || draft.serverPort() < 1 || draft.serverPort() > 65535) {
            throw invalid("Server port must be between 1 and 65535");
        }
        if (draft.port() != null && (draft.port() < 1 || draft.port() > 65535)) {
            throw invalid("Public port must be between 1 and 65535");
        }
        NodeMachine machine = machine(draft.machineId());
        validateReferences(draft, type);
        Map<String, Object> protocolSettings = draft.protocolSettings() == null
            ? new java.util.LinkedHashMap<>()
            : new java.util.LinkedHashMap<>(draft.protocolSettings());
        addServerKeyWhenRequired(type, protocolSettings);
        validateProtocolSettings(type, protocolSettings);
        node.configure(
            type, blankToNull(draft.code()), draft.parentId(), machine,
            json(draft.groupIds() == null ? List.of() : deduplicate(draft.groupIds())),
            json(draft.routeIds() == null ? List.of() : deduplicate(draft.routeIds())),
            draft.name().trim(), draft.rate() == null ? BigDecimal.ONE : draft.rate(),
            Boolean.TRUE.equals(draft.rateTimeEnable()),
            json(draft.rateTimeRanges() == null ? List.of() : draft.rateTimeRanges()),
            draft.transferEnable() == null ? 0 : Math.max(draft.transferEnable(), 0),
            json(draft.tags() == null ? List.of() : draft.tags()), blankToNull(draft.host()),
            draft.port(), draft.serverPort(),
            json(protocolSettings),
            json(draft.customOutbounds() == null ? List.of() : draft.customOutbounds()),
            json(draft.customRoutes() == null ? List.of() : draft.customRoutes()),
            draft.certConfig() == null ? null : json(draft.certConfig()),
            draft.show() == null || draft.show(), draft.enabled() == null || draft.enabled(),
            draft.sort() == null ? defaultSort : draft.sort(), clock.instant()
        );
    }

    private NodeMachine machine(Long id) {
        if (id == null) return null;
        return machines.findById(id).orElseThrow(() -> new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT, "MACHINE_NOT_FOUND", "Bound machine does not exist"
        ));
    }

    private void validateReferences(NodeDraft draft, String type) {
        List<Long> groupIds = draft.groupIds() == null ? List.of() : deduplicate(draft.groupIds());
        if (groups.findAllById(groupIds).size() != groupIds.size()) {
            throw invalid("One or more node permission groups do not exist");
        }
        List<Long> routeIds = draft.routeIds() == null ? List.of() : deduplicate(draft.routeIds());
        if (routes.findAllById(routeIds).size() != routeIds.size()) {
            throw invalid("One or more node routes do not exist");
        }
        if (draft.parentId() == null) return;
        if (draft.id() != null && draft.parentId().equals(draft.id())) {
            throw invalid("A node cannot be its own parent");
        }
        ProxyNode parent = requireNode(draft.parentId());
        if (!type.equals(parent.getType())) {
            throw invalid("Parent and child nodes must use the same protocol");
        }
        if (parent.getParentId() != null) {
            throw invalid("A child node cannot be selected as another node's parent");
        }
    }

    private void validateProtocolSettings(String type, Map<String, Object> settings) {
        switch (type) {
            case "shadowsocks" -> requireText(settings, "cipher", "Shadowsocks cipher is required");
            case "vmess", "vless" -> {
                requireNumber(settings, "tls", "TLS mode is required");
                requireText(settings, "network", "Transport protocol is required");
            }
            case "trojan" -> requireText(settings, "network", "Transport protocol is required");
            case "hysteria" -> requireNumber(settings, "version", "Hysteria version is required");
            case "mieru" -> {
                String transport = requireText(settings, "transport", "Mieru transport is required");
                if (!Set.of("TCP", "UDP").contains(transport.toUpperCase(Locale.ROOT))) {
                    throw invalid("Mieru transport must be TCP or UDP");
                }
                requireText(settings, "traffic_pattern", "Mieru traffic pattern is required");
            }
            default -> { }
        }
    }

    private String requireText(Map<String, Object> settings, String key, String message) {
        Object value = settings.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(message);
        return text.trim();
    }

    private void requireNumber(Map<String, Object> settings, String key, String message) {
        if (!(settings.get(key) instanceof Number)) throw invalid(message);
    }

    private byte[] littleEndian(BigInteger value, int length) {
        byte[] bigEndian = value.toByteArray();
        byte[] result = new byte[length];
        int count = Math.min(length, bigEndian.length);
        for (int index = 0; index < count; index++) {
            result[index] = bigEndian[bigEndian.length - 1 - index];
        }
        return result;
    }

    private void writeU8(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
    }

    private void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private String pem(String label, byte[] value) {
        String encoded = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(value);
        return "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----";
    }

    private int nextSort() {
        return nodes.findAllByOrderBySortOrderAscIdAsc().stream()
            .mapToInt(ProxyNode::getSortOrder).max().orElse(-1) + 1;
    }

    private void addServerKeyWhenRequired(String type, Map<String, Object> settings) {
        if (!"shadowsocks".equals(type) || settings.containsKey("server_key")) return;
        String cipher = String.valueOf(settings.getOrDefault("cipher", ""));
        int length = switch (cipher) {
            case "2022-blake3-aes-128-gcm" -> 16;
            case "2022-blake3-aes-256-gcm" -> 32;
            default -> 0;
        };
        if (length > 0) {
            byte[] key = new byte[length];
            RANDOM.nextBytes(key);
            settings.put("server_key", Base64.getEncoder().encodeToString(key));
        }
    }

    private NodeView view(ProxyNode node) {
        return new NodeView(
            node.getId(), node.getType(), node.getCode(), node.getParentId(),
            node.getMachine() == null ? null : node.getMachine().getId(),
            jsonList(node.getGroupIds()), jsonList(node.getRouteIds()), node.getName(),
            node.getRate(), node.isRateTimeEnable(), jsonValue(node.getRateTimeRanges()),
            node.getTransferEnable(), node.getUploadBytes(), node.getDownloadBytes(),
            jsonStringList(node.getTags()), node.getHost(), node.getPort(), node.getServerPort(),
            jsonMap(node.getProtocolSettings()), jsonValue(node.getCustomOutbounds()),
            jsonValue(node.getCustomRoutes()), nullableJsonValue(node.getCertConfig()),
            node.isShow(), node.isEnabled(), node.getSortOrder(), node.getOnlineUsers(),
            node.getOnlineConnections(), nullableJsonValue(node.getLoadStatus()),
            nullableJsonValue(node.getMetrics()), epoch(node.getLastCheckAt()),
            epoch(node.getLastPushAt()), node.getCreatedAt().getEpochSecond(),
            node.getUpdatedAt().getEpochSecond()
        );
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw invalid("Node configuration contains invalid JSON data"); }
    }

    @SuppressWarnings("unchecked")
    private List<Long> jsonList(String value) {
        try { return objectMapper.readValue(value, List.class).stream().map(item -> Long.valueOf(item.toString())).toList(); }
        catch (Exception exception) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(String value) {
        try { return objectMapper.readValue(value, Map.class); }
        catch (Exception exception) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<String> jsonStringList(String value) {
        try { return objectMapper.readValue(value, List.class).stream().map(Object::toString).toList(); }
        catch (Exception exception) { return List.of(); }
    }

    private Object jsonValue(String value) {
        try { return objectMapper.readValue(value, Object.class); }
        catch (Exception exception) { return List.of(); }
    }

    private Object nullableJsonValue(String value) {
        return value == null ? null : jsonValue(value);
    }

    private List<Long> deduplicate(List<Long> values) {
        if (values.stream().anyMatch(value -> value == null || value <= 0)) {
            throw invalid("Node association ids must be positive integers");
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private Long epoch(Instant value) { return value == null ? null : value.getEpochSecond(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_NODE", detail);
    }

    public record NodeDraft(
        Long id, String type, String code, Long parentId, Long machineId,
        List<Long> groupIds, List<Long> routeIds, String name, BigDecimal rate,
        Boolean rateTimeEnable, Object rateTimeRanges, Long transferEnable,
        List<String> tags, String host, Integer port, Integer serverPort,
        Map<String, Object> protocolSettings, Object customOutbounds,
        Object customRoutes, Object certConfig, Boolean show, Boolean enabled,
        Integer sort
    ) {}

    public record SortItem(long id, int order) {}

    public record EchKeyPair(String key, String config) {}

    public record NodeView(
        long id, String type, String code,
        @JsonProperty("parent_id") Long parentId,
        @JsonProperty("machine_id") Long machineId,
        @JsonProperty("group_ids") List<Long> groupIds,
        @JsonProperty("route_ids") List<Long> routeIds,
        String name, BigDecimal rate,
        @JsonProperty("rate_time_enable") boolean rateTimeEnable,
        @JsonProperty("rate_time_ranges") Object rateTimeRanges,
        @JsonProperty("transfer_enable") long transferEnable,
        @JsonProperty("u") long uploadBytes,
        @JsonProperty("d") long downloadBytes,
        List<String> tags, String host, Integer port,
        @JsonProperty("server_port") int serverPort,
        @JsonProperty("protocol_settings") Map<String, Object> protocolSettings,
        @JsonProperty("custom_outbounds") Object customOutbounds,
        @JsonProperty("custom_routes") Object customRoutes,
        @JsonProperty("cert_config") Object certConfig,
        boolean show, boolean enabled, int sort, int onlineUsers,
        @JsonProperty("online_conn") int onlineConnections,
        @JsonProperty("load_status") Object loadStatus, Object metrics,
        @JsonProperty("last_check_at") Long lastCheckAt,
        @JsonProperty("last_push_at") Long lastPushAt,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) {}
}
