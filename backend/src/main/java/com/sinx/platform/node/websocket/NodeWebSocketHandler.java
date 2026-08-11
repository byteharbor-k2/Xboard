package com.sinx.platform.node.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.node.application.NodeDeviceStateService;
import com.sinx.platform.node.application.NodeProtocolService;

import tools.jackson.databind.ObjectMapper;

@Component
public class NodeWebSocketHandler extends TextWebSocketHandler {

    private static final long MAX_IDLE_SECONDS = 125;
    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeWebSocketHandler.class
    );

    private final NodeWebSocketRegistry registry;
    private final NodeWebSocketAuthenticator authenticator;
    private final NodeWebSocketSyncService sync;
    private final NodeProtocolService protocol;
    private final NodeDeviceStateService devices;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PlatformConfigurationService configuration;
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();

    public NodeWebSocketHandler(
        NodeWebSocketRegistry registry,
        NodeWebSocketAuthenticator authenticator,
        NodeWebSocketSyncService sync,
        NodeProtocolService protocol,
        NodeDeviceStateService devices,
        ObjectMapper objectMapper,
        Clock clock,
        PlatformConfigurationService configuration
    ) {
        this.registry = registry;
        this.authenticator = authenticator;
        this.sync = sync;
        this.protocol = protocol;
        this.devices = devices;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.configuration = configuration;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!webSocketEnabled()) {
            session.close(CloseStatus.SERVICE_RESTARTED.withReason(
                "WebSocket communication disabled"
            ));
            return;
        }
        Object value = session.getAttributes().get(NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT);
        if (!(value instanceof NodeWebSocketAuthContext pendingAuth)) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Authentication required"));
            return;
        }
        NodeWebSocketAuthContext auth;
        try {
            // The HTTP upgrade and WebSocket establishment are separate steps.
            // Revalidate here so a token revoked between them cannot replace a
            // still-valid existing connection in the registry.
            auth = authenticator.refresh(pendingAuth);
        } catch (RuntimeException exception) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Credentials revoked"));
            return;
        }
        registry.register(session, auth);
        if (!webSocketEnabled()) {
            registry.unregister(session);
            session.close(CloseStatus.SERVICE_RESTARTED.withReason(
                "WebSocket communication disabled"
            ));
            return;
        }
        try {
            // Close the remaining race where credentials are revoked after the
            // first validation but before registration. Admin revocation also
            // disconnects registered sessions, so this second check covers the
            // only interval in which the notifier could have run too early.
            NodeWebSocketAuthContext confirmed = authenticator.refresh(auth);
            if (confirmed.machineMode()) {
                registry.refreshMachineNodes(session, confirmed.nodeIds());
            }
            auth = confirmed;
        } catch (RuntimeException exception) {
            registry.unregister(session);
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Credentials revoked"));
            return;
        }
        if (!session.isOpen() || registry.auth(session) == null) return;
        lastActivity.put(session.getId(), clock.instant());
        Map<String, Object> response = new LinkedHashMap<>();
        if (auth.machineMode()) {
            response.put("machine_id", auth.machineId());
            response.put("node_ids", auth.nodeIds());
        } else {
            response.put("node_id", auth.nodeId());
        }
        sync.send(session, "auth.success", response);
        sync.fullSync(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        lastActivity.put(session.getId(), clock.instant());
        Map<String, Object> envelope = map(message.getPayload());
        String event = string(envelope.get("event"));
        Map<String, Object> data = envelope.get("data") instanceof Map<?, ?> raw
            ? stringMap(raw)
            : new LinkedHashMap<>();
        NodeWebSocketAuthContext auth = registry.auth(session);
        if (auth == null || event == null) {
            session.close(CloseStatus.BAD_DATA.withReason("Invalid event envelope"));
            return;
        }
        if ("pong".equals(event)) return;
        Long nodeId = resolveNodeId(auth, data);
        if (nodeId == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("Invalid node scope"));
            return;
        }
        data.remove("node_id");
        switch (event) {
            case "node.status" -> {
                Map<String, Object> status = Map.of("metrics", data);
                if (auth.machineMode()) {
                    protocol.report(auth.machineId(), nodeId, auth.token(), status);
                } else {
                    protocol.reportLegacy(nodeId, auth.token(), status);
                }
            }
            case "report.devices" -> devices.replaceSnapshot(
                nodeId,
                deviceSnapshot(data.get("devices") instanceof Map<?, ?> nested ? nested : data),
                clock.instant()
            );
            case "request.devices" -> sendDevices(session, auth, nodeId);
            default -> sync.send(session, "error", Map.of("message", "Unsupported event"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lastActivity.remove(session.getId());
        registry.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        lastActivity.remove(session.getId());
        registry.unregister(session);
        if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR);
    }

    @Scheduled(fixedDelay = 55_000)
    public void heartbeat() {
        Instant now = clock.instant();
        for (WebSocketSession session : registry.sessions()) {
            Instant activity = lastActivity.get(session.getId());
            if (activity == null || activity.plusSeconds(MAX_IDLE_SECONDS).isBefore(now)) {
                lastActivity.remove(session.getId());
                registry.unregister(session);
                closeQuietly(session, CloseStatus.SESSION_NOT_RELIABLE.withReason("Heartbeat timeout"));
                continue;
            }
            sync.send(session, "ping", Map.of());
        }
        try {
            devices.pruneExpired(now);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not prune expired WebSocket device state", exception);
        }
    }

    private void sendDevices(
        WebSocketSession session,
        NodeWebSocketAuthContext auth,
        long nodeId
    ) {
        Set<Long> userIds = new LinkedHashSet<>();
        NodeProtocolService.UsersPayload users = auth.machineMode()
            ? protocol.users(auth.machineId(), nodeId, auth.token())
            : protocol.usersLegacy(nodeId, auth.token());
        users.users().forEach(user -> {
            Object id = user.get("id");
            if (id instanceof Number number && number.longValue() > 0) {
                userIds.add(number.longValue());
            }
        });
        sync.sendDevices(session, nodeId, devices.snapshotForUsers(userIds, clock.instant()));
    }

    private Long resolveNodeId(NodeWebSocketAuthContext auth, Map<String, Object> data) {
        if (!auth.machineMode()) return auth.nodeId();
        Object raw = data.get("node_id");
        long parsed = raw instanceof Number number ? number.longValue() : -1;
        return parsed > 0 && auth.nodeIds().contains(parsed) ? parsed : null;
    }

    private Map<Long, List<String>> deviceSnapshot(Map<?, ?> raw) {
        Map<Long, List<String>> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            Long userId;
            try {
                userId = Long.parseLong(String.valueOf(key));
            } catch (RuntimeException exception) {
                return;
            }
            if (userId <= 0 || !(value instanceof List<?> addresses)) return;
            result.put(userId, addresses.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList());
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(String json) throws IOException {
        return objectMapper.readValue(json, Map.class);
    }

    private Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private String string(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
        }
    }

    private boolean webSocketEnabled() {
        try {
            return configuration.nodeCommunicationSettings().webSocketEnabled();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not read node WebSocket setting", exception);
            return false;
        }
    }
}
