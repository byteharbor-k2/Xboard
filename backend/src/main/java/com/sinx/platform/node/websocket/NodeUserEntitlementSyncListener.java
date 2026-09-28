package com.sinx.platform.node.websocket;

import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.node.application.NodeManagementService;

/**
 * Pushes an entitlement change (a purchase opening or renewing a
 * subscription, an administrator's correction, a traffic reset) to the nodes
 * that serve it.
 *
 * Shaped exactly like {@link NodeUserSuspensionSyncListener} - same phase,
 * same group filtering, same log-and-swallow - because the problem is the
 * same: the node kernel carries its own user list until it re-reads it, and
 * a change that alters what the list should hold has to be pushed, not left
 * to the next {@code pull_interval} poll.
 */
@Component
public class NodeUserEntitlementSyncListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeUserEntitlementSyncListener.class
    );

    private final NodeManagementService nodes;
    private final NodeWebSocketSyncService sync;

    public NodeUserEntitlementSyncListener(
        NodeManagementService nodes,
        NodeWebSocketSyncService sync
    ) {
        this.nodes = nodes;
        this.sync = sync;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void synchronize(UserEntitlementChangedEvent event) {
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
                "Could not resolve nodes after account {} entitlement changed",
                event.userId(),
                exception
            );
        }
    }

    private void pushUsers(long nodeId, UserEntitlementChangedEvent event) {
        try {
            sync.pushUsers(nodeId);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                "Could not synchronize users for node {} after account {} entitlement changed",
                nodeId,
                event.userId(),
                exception
            );
        }
    }
}
