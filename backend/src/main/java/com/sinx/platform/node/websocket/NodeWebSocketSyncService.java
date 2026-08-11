package com.sinx.platform.node.websocket;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeProtocolService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class NodeWebSocketSyncService {

    private final NodeWebSocketRegistry registry;
    private final NodeProtocolService protocol;
    private final NodeManagementService nodes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeWebSocketSyncService(
        NodeWebSocketRegistry registry,
        NodeProtocolService protocol,
        NodeManagementService nodes,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.registry = registry;
        this.protocol = protocol;
        this.nodes = nodes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void fullSync(org.springframework.web.socket.WebSocketSession session) {
        NodeWebSocketAuthContext auth = registry.auth(session);
        if (auth == null) return;
        for (Long nodeId : auth.nodeIds()) {
            sendConfig(session, auth, nodeId);
            sendUsers(session, auth, nodeId);
        }
    }

    public boolean pushConfig(long nodeId) {
        var session = registry.node(nodeId);
        NodeWebSocketAuthContext auth = session == null ? null : registry.auth(session);
        if (auth == null) return false;
        sendConfig(session, auth, nodeId);
        return true;
    }

    public boolean pushUsers(long nodeId) {
        var session = registry.node(nodeId);
        NodeWebSocketAuthContext auth = session == null ? null : registry.auth(session);
        if (auth == null) return false;
        sendUsers(session, auth, nodeId);
        return true;
    }

    public boolean pushUserDelta(
        long nodeId,
        String action,
        List<Map<String, Object>> users
    ) {
        if (!List.of("add", "remove").contains(action) || users.isEmpty()) {
            throw new IllegalArgumentException("User delta must contain add/remove users");
        }
        var session = registry.node(nodeId);
        NodeWebSocketAuthContext auth = session == null ? null : registry.auth(session);
        if (auth == null) return false;
        Map<String, Object> data = scopedData(auth, nodeId);
        data.put("action", action);
        data.put("users", users);
        return send(session, "sync.user.delta", data);
    }

    public boolean refreshMachine(long machineId) {
        var session = registry.machine(machineId);
        NodeWebSocketAuthContext auth = session == null ? null : registry.auth(session);
        if (auth == null) return false;
        List<NodeManagementService.NodeView> views = nodes.forMachine(machineId, true);
        List<Long> previous = auth.nodeIds();
        List<Long> current = views.stream().map(NodeManagementService.NodeView::id).toList();
        registry.refreshMachineNodes(session, current);
        List<Map<String, Object>> payload = views.stream().map(node -> Map.<String, Object>of(
            "id", node.id(),
            "type", node.type(),
            "name", node.name()
        )).toList();
        send(session, "sync.nodes", new LinkedHashMap<>(Map.of("nodes", payload)));
        current.stream().filter(nodeId -> !previous.contains(nodeId)).forEach(nodeId -> {
            NodeWebSocketAuthContext refreshed = registry.auth(session);
            sendConfig(session, refreshed, nodeId);
            sendUsers(session, refreshed, nodeId);
        });
        return true;
    }

    public boolean disconnectMachine(long machineId) {
        return registry.disconnectMachine(
            machineId,
            CloseStatus.POLICY_VIOLATION.withReason("Machine credentials revoked")
        );
    }

    public boolean disconnectLegacyNode(long nodeId) {
        return registry.disconnectLegacyNode(
            nodeId,
            CloseStatus.POLICY_VIOLATION.withReason("Node access revoked")
        );
    }

    public int disconnectAllConnections() {
        return registry.disconnectAll(
            CloseStatus.SERVICE_RESTARTED.withReason(
                "WebSocket communication disabled"
            )
        );
    }

    public int disconnectLegacyConnections() {
        return registry.disconnectLegacyConnections(
            CloseStatus.POLICY_VIOLATION.withReason(
                "Legacy node credentials rotated"
            )
        );
    }

    public boolean sendDevices(
        org.springframework.web.socket.WebSocketSession session,
        long nodeId,
        Map<Long, List<String>> devices
    ) {
        NodeWebSocketAuthContext auth = registry.auth(session);
        if (auth == null) return false;
        Map<String, Object> data = scopedData(auth, nodeId);
        data.put("users", devices);
        return send(session, "sync.devices", data);
    }

    private void sendConfig(
        org.springframework.web.socket.WebSocketSession session,
        NodeWebSocketAuthContext auth,
        long nodeId
    ) {
        NodeProtocolService.ConfigPayload config = auth.machineMode()
            ? protocol.config(auth.machineId(), nodeId, auth.token())
            : protocol.configLegacy(nodeId, auth.token());
        Map<String, Object> data = scopedData(auth, nodeId);
        data.put("config", config.data());
        send(session, "sync.config", data);
    }

    private void sendUsers(
        org.springframework.web.socket.WebSocketSession session,
        NodeWebSocketAuthContext auth,
        long nodeId
    ) {
        NodeProtocolService.UsersPayload users = auth.machineMode()
            ? protocol.users(auth.machineId(), nodeId, auth.token())
            : protocol.usersLegacy(nodeId, auth.token());
        Map<String, Object> data = scopedData(auth, nodeId);
        data.put("users", users.users());
        send(session, "sync.users", data);
    }

    private Map<String, Object> scopedData(NodeWebSocketAuthContext auth, long nodeId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timestamp", clock.instant().getEpochSecond());
        if (auth.machineMode()) data.put("node_id", nodeId);
        return data;
    }

    boolean send(
        org.springframework.web.socket.WebSocketSession session,
        String event,
        Map<String, Object> data
    ) {
        try {
            return registry.send(session, objectMapper.writeValueAsString(Map.of(
                "event", event,
                "data", data,
                "timestamp", clock.instant().getEpochSecond()
            )));
        } catch (JacksonException exception) {
            throw new IllegalStateException("Could not encode node WebSocket event", exception);
        }
    }
}
