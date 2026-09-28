package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.node.application.NodeManagementService;

class NodeUserEntitlementSyncListenerTest {

    private final NodeManagementService nodes = mock(NodeManagementService.class);
    private final NodeWebSocketSyncService sync = mock(NodeWebSocketSyncService.class);
    private final NodeUserEntitlementSyncListener listener =
        new NodeUserEntitlementSyncListener(nodes, sync);

    @Test
    void runsOnlyAfterThePublishingTransactionCommits() throws Exception {
        TransactionalEventListener annotation = NodeUserEntitlementSyncListener.class
            .getMethod("synchronize", UserEntitlementChangedEvent.class)
            .getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void pushesEachEnabledNodeServingTheAccountGroupsOnce() {
        when(nodes.list()).thenReturn(List.of(
            node(1L, true, List.of(10L)),
            node(2L, true, List.of(10L, 20L)),
            node(3L, false, List.of(10L))
        ));

        listener.synchronize(event);

        verify(sync).pushUsers(1L);
        verify(sync).pushUsers(2L);
        verify(sync, never()).pushUsers(3L);
    }

    @Test
    void skipsEnabledNodesOutsideTheAccountGroups() {
        when(nodes.list()).thenReturn(List.of(
            node(1L, true, List.of(30L))
        ));

        listener.synchronize(event);

        verifyNoInteractions(sync);
    }

    @Test
    void ignoresAnEventWithoutValidGroups() {
        listener.synchronize(new UserEntitlementChangedEvent(
            UUID.randomUUID(),
            List.of(),
            Instant.now()
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

        assertThatCode(() -> listener.synchronize(event))
            .doesNotThrowAnyException();

        verify(sync).pushUsers(2L);
    }

    private final UserEntitlementChangedEvent event = new UserEntitlementChangedEvent(
        UUID.randomUUID(),
        List.of(10L),
        Instant.now()
    );

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
