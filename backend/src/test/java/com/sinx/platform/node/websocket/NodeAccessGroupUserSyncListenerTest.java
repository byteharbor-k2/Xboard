package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.node.application.NodeAccessGroupsChangedEvent;
import com.sinx.platform.node.application.NodeManagementService;

class NodeAccessGroupUserSyncListenerTest {

    private final NodeManagementService nodes = mock(NodeManagementService.class);
    private final NodeWebSocketSyncService sync = mock(NodeWebSocketSyncService.class);
    private final NodeAccessGroupUserSyncListener listener =
        new NodeAccessGroupUserSyncListener(nodes, sync);

    @Test
    void runsOnlyAfterThePublishingTransactionCommits() throws Exception {
        TransactionalEventListener annotation = NodeAccessGroupUserSyncListener.class
            .getMethod("synchronize", NodeAccessGroupsChangedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void synchronizesEachEnabledAffectedNodeOnce() {
        when(nodes.list()).thenReturn(List.of(
            node(1L, true, List.of(10L)),
            node(1L, true, List.of(10L, 20L)),
            node(2L, true, List.of(20L)),
            node(3L, true, List.of(30L)),
            node(4L, false, List.of(10L))
        ));

        listener.synchronize(NodeAccessGroupsChangedEvent.of(
            Set.of(10L, 20L),
            "test change"
        ));

        verify(sync).pushUsers(1L);
        verify(sync).pushUsers(2L);
        verify(sync, never()).pushUsers(3L);
        verify(sync, never()).pushUsers(4L);
    }

    @Test
    void ignoresAnEventWithoutValidGroups() {
        listener.synchronize(NodeAccessGroupsChangedEvent.of(
            List.of(),
            "empty change"
        ));

        verifyNoInteractions(nodes, sync);
    }

    @Test
    void oneDisconnectedNodeDoesNotPreventOtherNodeSynchronization() {
        when(nodes.list()).thenReturn(List.of(
            node(1L, true, List.of(10L)),
            node(2L, true, List.of(10L))
        ));
        when(sync.pushUsers(1L)).thenThrow(
            new IllegalStateException("socket disconnected")
        );

        assertThatCode(() -> listener.synchronize(
            NodeAccessGroupsChangedEvent.of(Set.of(10L), "test change")
        )).doesNotThrowAnyException();

        verify(sync).pushUsers(2L);
    }

    @Test
    void nodeLookupFailureDoesNotEscapeAfterCommitListener() {
        when(nodes.list()).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> listener.synchronize(
            NodeAccessGroupsChangedEvent.of(Set.of(10L), "test change")
        )).doesNotThrowAnyException();

        verifyNoInteractions(sync);
    }

    private NodeManagementService.NodeView node(
        long id,
        boolean enabled,
        List<Long> groupIds
    ) {
        return new NodeManagementService.NodeView(
            id,
            "vless",
            "node-" + id,
            null,
            1L,
            groupIds,
            List.of(),
            "Node " + id,
            BigDecimal.ONE,
            false,
            List.of(),
            0,
            0,
            0,
            List.of(),
            "node.example.test",
            443,
            8443,
            Map.of("network", "tcp"),
            List.of(),
            List.of(),
            null,
            true,
            enabled,
            0,
            0,
            0,
            null,
            null,
            null,
            null,
            100,
            200
        );
    }
}
