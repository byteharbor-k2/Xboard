package com.sinx.platform.node.websocket;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sinx.platform.node.application.NodeManagementService;

/**
 * Translates committed admin mutations into the same node WebSocket updates
 * emitted by Xboard's ServerObserver and ServerRouteObserver.
 */
@Service
public class NodeWebSocketChangeNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeWebSocketChangeNotifier.class
    );

    private final NodeWebSocketSyncService sync;

    public NodeWebSocketChangeNotifier(NodeWebSocketSyncService sync) {
        this.sync = sync;
    }

    public void nodeCreated(NodeManagementService.NodeView node) {
        refreshMachines(machineIds(List.of(node)));
    }

    public void nodesUpdated(
        Collection<NodeManagementService.NodeView> before,
        Collection<NodeManagementService.NodeView> after
    ) {
        Map<Long, NodeManagementService.NodeView> previous = byId(before);
        Map<Long, NodeManagementService.NodeView> current = byId(after);
        Set<Long> machinesToRefresh = new LinkedHashSet<>();
        Set<Long> fullSyncNodes = new LinkedHashSet<>();
        Set<Long> configSyncNodes = new LinkedHashSet<>();

        for (Map.Entry<Long, NodeManagementService.NodeView> entry : current.entrySet()) {
            NodeManagementService.NodeView oldNode = previous.get(entry.getKey());
            if (oldNode == null) {
                addMachine(machinesToRefresh, entry.getValue().machineId());
                continue;
            }
            NodeManagementService.NodeView newNode = entry.getValue();
            if (membershipChanged(oldNode, newNode)) {
                addMachine(machinesToRefresh, oldNode.machineId());
                addMachine(machinesToRefresh, newNode.machineId());
                if (!newNode.enabled()) {
                    safely(
                        "legacy connection cleanup for node " + newNode.id(),
                        () -> sync.disconnectLegacyNode(newNode.id())
                    );
                }
                continue;
            }
            if (!Objects.equals(oldNode.groupIds(), newNode.groupIds())) {
                fullSyncNodes.add(newNode.id());
            } else if (configurationChanged(oldNode, newNode)) {
                configSyncNodes.add(newNode.id());
            }
        }

        // Update the registry first when a node moved or was enabled/disabled.
        // refreshMachine also performs the initial config/users sync for newly
        // assigned nodes, so a second node-scoped sync would be redundant.
        refreshMachines(machinesToRefresh);
        fullSyncNodes.forEach(nodeId -> fullSync(current.get(nodeId)));
        configSyncNodes.forEach(nodeId -> deliverConfig(current.get(nodeId)));
    }

    public void nodesDeleted(Collection<NodeManagementService.NodeView> nodes) {
        nodes.forEach(node -> safely(
            "legacy connection cleanup for node " + node.id(),
            () -> sync.disconnectLegacyNode(node.id())
        ));
        refreshMachines(machineIds(nodes));
    }

    public void routeChanged(
        long routeId,
        Collection<NodeManagementService.NodeView> nodeSnapshot
    ) {
        nodeSnapshot.stream()
            .filter(NodeManagementService.NodeView::show)
            .filter(node -> node.routeIds().contains(routeId))
            .forEach(this::deliverConfig);
    }

    public void machineDeleted(long machineId) {
        safely(
            "connection cleanup for deleted machine " + machineId,
            () -> sync.disconnectMachine(machineId)
        );
    }

    public void machineRevoked(long machineId) {
        safely(
            "connection cleanup for revoked machine " + machineId,
            () -> sync.disconnectMachine(machineId)
        );
    }

    private void fullSync(NodeManagementService.NodeView node) {
        if (node == null) return;
        boolean config = deliver(
            "config sync for node " + node.id(),
            () -> sync.pushConfig(node.id())
        );
        boolean users = deliver(
            "user sync for node " + node.id(),
            () -> sync.pushUsers(node.id())
        );
        if (!config || !users) rediscoverOnMachine(node, "group membership");
    }

    private void deliverConfig(NodeManagementService.NodeView node) {
        if (node == null) return;
        boolean delivered = deliver(
            "config sync for node " + node.id(),
            () -> sync.pushConfig(node.id())
        );
        if (!delivered) rediscoverOnMachine(node, "configuration");
    }

    /**
     * A node-scoped push only reaches a node holding a live WebSocket session.
     * A node whose kernel failed to start has none, so the corrected settings
     * would be dropped and the node could never be repaired from the panel.
     * Falling back to a machine-level node-list sync makes a machine-mode agent
     * rediscover the node and start it with the new configuration.
     */
    private void rediscoverOnMachine(
        NodeManagementService.NodeView node,
        String change
    ) {
        if (node.machineId() == null) {
            LOGGER.info(
                "Node {} holds no session; {} change applies on its next poll",
                node.id(),
                change
            );
            return;
        }
        LOGGER.info(
            "Node {} holds no session; refreshing machine {} to redeliver the {} change",
            node.id(),
            node.machineId(),
            change
        );
        deliver(
            "node-list fallback sync for machine " + node.machineId(),
            () -> sync.refreshMachine(node.machineId())
        );
    }

    private void refreshMachines(Collection<Long> machineIds) {
        machineIds.forEach(machineId -> safely(
            "node-list sync for machine " + machineId,
            () -> sync.refreshMachine(machineId)
        ));
    }

    private Set<Long> machineIds(
        Collection<NodeManagementService.NodeView> nodes
    ) {
        Set<Long> result = new LinkedHashSet<>();
        nodes.forEach(node -> addMachine(result, node.machineId()));
        return result;
    }

    private void addMachine(Set<Long> result, Long machineId) {
        if (machineId != null) result.add(machineId);
    }

    private Map<Long, NodeManagementService.NodeView> byId(
        Collection<NodeManagementService.NodeView> nodes
    ) {
        Map<Long, NodeManagementService.NodeView> result = new LinkedHashMap<>();
        nodes.forEach(node -> result.put(node.id(), node));
        return result;
    }

    private boolean membershipChanged(
        NodeManagementService.NodeView before,
        NodeManagementService.NodeView after
    ) {
        return !Objects.equals(before.machineId(), after.machineId())
            || before.enabled() != after.enabled();
    }

    private boolean configurationChanged(
        NodeManagementService.NodeView before,
        NodeManagementService.NodeView after
    ) {
        return !Objects.equals(before.serverPort(), after.serverPort())
            || !Objects.equals(before.protocolSettings(), after.protocolSettings())
            || !Objects.equals(before.type(), after.type())
            || !Objects.equals(before.routeIds(), after.routeIds())
            || !Objects.equals(before.customOutbounds(), after.customOutbounds())
            || !Objects.equals(before.customRoutes(), after.customRoutes())
            || !Objects.equals(before.certConfig(), after.certConfig());
    }

    private boolean deliver(String operation, BooleanSupplier callback) {
        try {
            return callback.getAsBoolean();
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not deliver {}", operation, exception);
            return false;
        }
    }

    private void safely(String operation, Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException exception) {
            // The database mutation has already committed. A disconnected or
            // stale node must not turn a successful admin mutation into 5xx;
            // the next reconnect performs a full sync.
            LOGGER.warn("Could not deliver {}", operation, exception);
        }
    }
}
