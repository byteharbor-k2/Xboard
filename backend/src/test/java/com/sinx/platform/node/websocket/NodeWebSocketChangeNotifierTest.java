package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.node.application.NodeManagementService;

class NodeWebSocketChangeNotifierTest {

    private final NodeWebSocketSyncService sync = mock(NodeWebSocketSyncService.class);
    private final NodeWebSocketChangeNotifier notifier =
        new NodeWebSocketChangeNotifier(sync);

    @Test
    void groupChangePushesConfigAndUsers() {
        NodeManagementService.NodeView before = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView after = node(
            1, 10L, true, true, List.of(4L), List.of(7L), 8443
        );

        notifier.nodesUpdated(List.of(before), List.of(after));

        verify(sync).pushConfig(1);
        verify(sync).pushUsers(1);
        verify(sync, never()).refreshMachine(10);
    }

    @Test
    void nodeConfigChangePushesOnlyConfig() {
        NodeManagementService.NodeView before = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView after = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 9443
        );

        notifier.nodesUpdated(List.of(before), List.of(after));

        verify(sync).pushConfig(1);
        verify(sync, never()).pushUsers(1);
        verify(sync, never()).refreshMachine(10);
    }

    @Test
    void movingNodeRefreshesBothMachinesAndLetsRefreshPerformInitialSync() {
        NodeManagementService.NodeView before = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView after = node(
            1, 11L, true, true, List.of(4L), List.of(8L), 9443
        );

        notifier.nodesUpdated(List.of(before), List.of(after));

        verify(sync).refreshMachine(10);
        verify(sync).refreshMachine(11);
        verify(sync, never()).pushConfig(1);
        verify(sync, never()).pushUsers(1);
    }

    @Test
    void disablingNodeDisconnectsLegacySocketAndRefreshesMachineScope() {
        NodeManagementService.NodeView before = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView after = node(
            1, 10L, true, false, List.of(3L), List.of(7L), 8443
        );

        notifier.nodesUpdated(List.of(before), List.of(after));

        verify(sync).disconnectLegacyNode(1L);
        verify(sync).refreshMachine(10L);
    }

    @Test
    void routeChangeOnlyPushesVisibleReferencingNodes() {
        List<NodeManagementService.NodeView> nodes = List.of(
            node(1, 10L, true, true, List.of(3L), List.of(7L), 8443),
            node(2, 10L, false, true, List.of(3L), List.of(7L), 8443),
            node(3, 10L, true, true, List.of(3L), List.of(8L), 8443)
        );

        notifier.routeChanged(7, nodes);

        verify(sync).pushConfig(1);
        verify(sync, never()).pushConfig(2);
        verify(sync, never()).pushConfig(3);
    }

    @Test
    void machineRefreshesAreDeduplicatedForCreatedAndDeletedNodes() {
        NodeManagementService.NodeView first = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView second = node(
            2, 10L, true, true, List.of(3L), List.of(7L), 8443
        );

        notifier.nodesDeleted(List.of(first, second));

        verify(sync).disconnectLegacyNode(1L);
        verify(sync).disconnectLegacyNode(2L);
        verify(sync).refreshMachine(10);
    }

    @Test
    void deletingOrRevokingMachineActivelyDisconnectsItsSocket() {
        notifier.machineDeleted(20L);
        notifier.machineRevoked(21L);

        verify(sync).disconnectMachine(20L);
        verify(sync).disconnectMachine(21L);
    }

    @Test
    void disconnectFailureDoesNotFailCommittedMachineMutation() {
        when(sync.disconnectMachine(20L)).thenThrow(
            new IllegalStateException("socket already gone")
        );

        assertThatCode(() -> notifier.machineDeleted(20L))
            .doesNotThrowAnyException();
    }

    @Test
    void deliveryFailureDoesNotFailCommittedAdminMutation() {
        NodeManagementService.NodeView before = node(
            1, 10L, true, true, List.of(3L), List.of(7L), 8443
        );
        NodeManagementService.NodeView after = node(
            1, 10L, true, true, List.of(4L), List.of(7L), 8443
        );
        when(sync.pushConfig(1)).thenThrow(new IllegalStateException("gone"));

        assertThatCode(() -> notifier.nodesUpdated(List.of(before), List.of(after)))
            .doesNotThrowAnyException();
        verify(sync).pushUsers(1);
    }

    private NodeManagementService.NodeView node(
        long id,
        Long machineId,
        boolean show,
        boolean enabled,
        List<Long> groupIds,
        List<Long> routeIds,
        int serverPort
    ) {
        return new NodeManagementService.NodeView(
            id,
            "vless",
            "node-" + id,
            null,
            machineId,
            groupIds,
            routeIds,
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
            serverPort,
            Map.of("network", "tcp", "tls", 1),
            List.of(),
            List.of(),
            null,
            show,
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
