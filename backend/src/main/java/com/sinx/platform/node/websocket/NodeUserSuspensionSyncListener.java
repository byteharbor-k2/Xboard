package com.sinx.platform.node.websocket;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.identity.application.UserSuspensionChangedEvent;
import com.sinx.platform.node.application.NodeManagementService;

/**
 * Pushes an account suspension (or its lifting) to the nodes that serve it.
 *
 * Shaped like {@link NodeAccessGroupUserSyncListener} - same phase, same
 * log-and-swallow - but triggered from the identity module. The node user lists
 * filter on {@code users.status}, so the push is what makes xboard-node drop a
 * banned account from its kernel immediately instead of at its next poll (up to
 * {@code pull_interval}), and equally what puts the account back the moment
 * the ban is lifted.
 */
@Component
public class NodeUserSuspensionSyncListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeUserSuspensionSyncListener.class
    );

    private final NodeManagementService nodes;
    private final NodeWebSocketSyncService sync;

    public NodeUserSuspensionSyncListener(
        NodeManagementService nodes,
        NodeWebSocketSyncService sync
    ) {
        this.nodes = nodes;
        this.sync = sync;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void synchronize(UserSuspensionChangedEvent event) {
        Set<Long> groupIds = new LinkedHashSet<>(event.groupIds());
        if (groupIds.isEmpty()) return;
        try {
            nodes.list().stream()
                .filter(NodeManagementService.NodeView::enabled)
                .filter(node -> node.groupIds().stream().anyMatch(groupIds::contains))
                .map(NodeManagementService.NodeView::id)
                .distinct()
                .forEach(nodeId -> pushUsers(nodeId, event));
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not resolve nodes after account {} suspension changed",
                event.userId(),
                exception
            );
        }
    }

    private void pushUsers(long nodeId, UserSuspensionChangedEvent event) {
        try {
            sync.pushUsers(nodeId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not synchronize users for node {} after account {} was {}",
                nodeId,
                event.userId(),
                event.suspended() ? "suspended" : "restored",
                exception
            );
        }
    }
}
