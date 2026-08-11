package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.configuration.application.NodeCommunicationSettingsChangedEvent;

class NodeCommunicationSettingsChangedListenerTest {

    private final NodeWebSocketSyncService sync = mock(NodeWebSocketSyncService.class);
    private final NodeCommunicationSettingsChangedListener listener =
        new NodeCommunicationSettingsChangedListener(sync);

    @Test
    void runsOnlyAfterTheSettingTransactionCommits() throws Exception {
        TransactionalEventListener annotation =
            NodeCommunicationSettingsChangedListener.class
                .getMethod("apply", NodeCommunicationSettingsChangedEvent.class)
                .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void disablingWebSocketDisconnectsMachineAndLegacyConnections() {
        listener.apply(new NodeCommunicationSettingsChangedEvent(true, true));

        verify(sync).disconnectAllConnections();
        verify(sync, never()).disconnectLegacyConnections();
    }

    @Test
    void rotatingLegacyTokenDisconnectsOnlyLegacyConnections() {
        listener.apply(new NodeCommunicationSettingsChangedEvent(true, false));

        verify(sync).disconnectLegacyConnections();
        verify(sync, never()).disconnectAllConnections();
    }

    @Test
    void closeFailureCannotFailAnAlreadyCommittedSettingChange() {
        when(sync.disconnectAllConnections()).thenThrow(
            new IllegalStateException("transport closed")
        );

        assertThatCode(() -> listener.apply(
            new NodeCommunicationSettingsChangedEvent(false, true)
        )).doesNotThrowAnyException();
    }
}
