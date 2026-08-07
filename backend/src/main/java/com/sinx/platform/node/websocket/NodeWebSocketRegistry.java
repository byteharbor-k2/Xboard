package com.sinx.platform.node.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.sinx.platform.node.application.NodeDeviceStateService;

@Component
public class NodeWebSocketRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeWebSocketRegistry.class
    );

    private final NodeDeviceStateService devices;
    private final Map<Long, WebSocketSession> machines = new ConcurrentHashMap<>();
    private final Map<Long, WebSocketSession> nodes = new ConcurrentHashMap<>();
    private final Map<String, NodeWebSocketAuthContext> connections = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public NodeWebSocketRegistry(NodeDeviceStateService devices) {
        this.devices = devices;
    }

    public synchronized void register(WebSocketSession session, NodeWebSocketAuthContext auth) {
        Set<WebSocketSession> replaced = new LinkedHashSet<>();
        if (auth.machineMode()) {
            addReplacement(replaced, machines.get(auth.machineId()), session);
        }
        auth.nodeIds().forEach(nodeId ->
            addReplacement(replaced, nodes.get(nodeId), session)
        );

        connections.put(session.getId(), auth);
        sessions.put(session.getId(), session);
        if (auth.machineMode()) {
            machines.put(auth.machineId(), session);
        }
        for (Long nodeId : auth.nodeIds()) {
            nodes.put(nodeId, session);
        }

        for (WebSocketSession previous : replaced) {
            detach(previous);
            closeQuietly(
                previous,
                CloseStatus.POLICY_VIOLATION.withReason("Replaced by a newer connection")
            );
        }
    }

    public synchronized void refreshMachineNodes(
        WebSocketSession session,
        List<Long> nodeIds
    ) {
        NodeWebSocketAuthContext current = connections.get(session.getId());
        if (current == null || !current.machineMode()) {
            return;
        }
        Set<Long> previous = new LinkedHashSet<>(current.nodeIds());
        Set<Long> refreshedIds = new LinkedHashSet<>(nodeIds);
        previous.stream()
            .filter(nodeId -> !refreshedIds.contains(nodeId))
            .filter(nodeId -> nodes.remove(nodeId, session))
            .forEach(this::safeClearNode);

        NodeWebSocketAuthContext refreshed = new NodeWebSocketAuthContext(
            current.machineId(), current.token(), null, List.copyOf(refreshedIds)
        );
        connections.put(session.getId(), refreshed);
        Set<WebSocketSession> replaced = new LinkedHashSet<>();
        refreshedIds.forEach(nodeId ->
            addReplacement(replaced, nodes.put(nodeId, session), session)
        );
        for (WebSocketSession previousSession : replaced) {
            detach(previousSession);
            closeQuietly(
                previousSession,
                CloseStatus.POLICY_VIOLATION.withReason(
                    "Node assigned to a newer connection"
                )
            );
        }
    }

    public NodeWebSocketAuthContext auth(WebSocketSession session) {
        return connections.get(session.getId());
    }

    public WebSocketSession node(long nodeId) {
        return open(nodes.get(nodeId));
    }

    public WebSocketSession machine(long machineId) {
        return open(machines.get(machineId));
    }

    public List<WebSocketSession> sessions() {
        return new ArrayList<>(sessions.values()).stream()
            .filter(WebSocketSession::isOpen)
            .toList();
    }

    public synchronized void unregister(WebSocketSession session) {
        detach(session);
    }

    public synchronized boolean disconnectMachine(long machineId, CloseStatus status) {
        WebSocketSession session = machines.get(machineId);
        if (session == null) return false;
        detach(session);
        closeQuietly(session, status);
        return true;
    }

    public synchronized boolean disconnectLegacyNode(long nodeId, CloseStatus status) {
        WebSocketSession session = nodes.get(nodeId);
        NodeWebSocketAuthContext auth = session == null
            ? null
            : connections.get(session.getId());
        if (auth == null || auth.machineMode()) return false;
        detach(session);
        closeQuietly(session, status);
        return true;
    }

    public boolean send(WebSocketSession session, String json) {
        if (session == null || !session.isOpen()) return false;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException exception) {
            unregister(session);
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return false;
        }
    }

    private void addReplacement(
        Set<WebSocketSession> replaced,
        WebSocketSession previous,
        WebSocketSession replacement
    ) {
        if (previous != null && !previous.getId().equals(replacement.getId())) {
            replaced.add(previous);
        }
    }

    private void detach(WebSocketSession session) {
        NodeWebSocketAuthContext auth = connections.remove(session.getId());
        sessions.remove(session.getId(), session);
        if (auth == null) return;
        if (auth.machineMode()) machines.remove(auth.machineId(), session);
        auth.nodeIds().stream()
            .filter(nodeId -> nodes.remove(nodeId, session))
            .forEach(this::safeClearNode);
    }

    private void safeClearNode(long nodeId) {
        try {
            devices.clearNode(nodeId);
        } catch (RuntimeException exception) {
            // Redis is an acceleration/state store here. Its temporary failure
            // must not leave a revoked WebSocket registered as active.
            LOGGER.warn("Could not clear device state for node {}", nodeId, exception);
        }
    }

    private WebSocketSession open(WebSocketSession session) {
        return session != null && session.isOpen() ? session : null;
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (IOException ignored) {
        }
    }
}
