package com.sinx.platform.node.websocket;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.node.application.NodeAccessGroupsChangedEvent;
import com.sinx.platform.node.application.NodeManagementService;

@Component
public class NodeAccessGroupUserSyncListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeAccessGroupUserSyncListener.class
    );

    private final NodeManagementService nodes;
    private final NodeWebSocketSyncService sync;

    public NodeAccessGroupUserSyncListener(
        NodeManagementService nodes,
        NodeWebSocketSyncService sync
    ) {
        this.nodes = nodes;
        this.sync = sync;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void synchronize(NodeAccessGroupsChangedEvent event) {
        Set<Long> groupIds = event.groupIds();
        if (groupIds.isEmpty()) return;
        try {
            nodes.list().stream()
                .filter(NodeManagementService.NodeView::enabled)
                .filter(node -> node.groupIds().stream().anyMatch(groupIds::contains))
                .map(NodeManagementService.NodeView::id)
                .distinct()
                .forEach(nodeId -> pushUsers(nodeId, event.reason()));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not resolve nodes after {}",
                event.reason(),
                exception
            );
        }
    }

    private void pushUsers(long nodeId, String reason) {
        try {
            sync.pushUsers(nodeId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not synchronize users for node {} after {}",
                nodeId,
                reason,
                exception
            );
        }
    }
}
