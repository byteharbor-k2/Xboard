package com.sinx.platform.node.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.configuration.application.NodeCommunicationSettingsChangedEvent;

@Component
public class NodeCommunicationSettingsChangedListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        NodeCommunicationSettingsChangedListener.class
    );

    private final NodeWebSocketSyncService sync;

    public NodeCommunicationSettingsChangedListener(
        NodeWebSocketSyncService sync
    ) {
        this.sync = sync;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void apply(NodeCommunicationSettingsChangedEvent event) {
        try {
            if (event.webSocketDisabled()) {
                sync.disconnectAllConnections();
            } else if (event.legacyTokenChanged()) {
                sync.disconnectLegacyConnections();
            }
        } catch (RuntimeException exception) {
            // The setting is already committed. A failed close cannot roll it
            // back; the handshake gate still prevents disabled or stale
            // credentials from establishing a new connection.
            LOGGER.warn(
                "Could not apply committed node communication setting change",
                exception
            );
        }
    }
}
