package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.sinx.platform.node.application.NodeDeviceStateService;

class NodeWebSocketRegistryTest {

    @Test
    void replacingConnectionDoesNotClearStateNowOwnedByNewConnection() throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        WebSocketSession oldSession = session("machine-old");
        WebSocketSession newSession = session("machine-new");
        NodeWebSocketAuthContext oldAuth = new NodeWebSocketAuthContext(
            4L, "old", null, List.of(11L, 12L)
        );
        NodeWebSocketAuthContext newAuth = new NodeWebSocketAuthContext(
            4L, "new", null, List.of(11L)
        );
        registry.register(oldSession, oldAuth);

        registry.register(newSession, newAuth);
        // Spring invokes this callback after close; it must be idempotent.
        registry.unregister(oldSession);

        assertThat(registry.machine(4L)).isSameAs(newSession);
        assertThat(registry.node(11L)).isSameAs(newSession);
        assertThat(registry.node(12L)).isNull();
        assertThat(registry.auth(oldSession)).isNull();
        assertThat(registry.auth(newSession)).isEqualTo(newAuth);
        verify(devices, never()).clearNode(11L);
        verify(devices, times(1)).clearNode(12L);
        verify(oldSession).close(any(CloseStatus.class));
    }

    @Test
    void machineRefreshClosesDisplacedLegacyConnectionWithoutClearingRetainedState()
        throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        WebSocketSession legacy = session("legacy-22");
        WebSocketSession machine = session("machine-7");
        registry.register(
            legacy,
            new NodeWebSocketAuthContext(0, "global", 22L, List.of(22L))
        );
        registry.register(
            machine,
            new NodeWebSocketAuthContext(7L, "machine", null, List.of(11L))
        );

        registry.refreshMachineNodes(machine, List.of(11L, 22L));

        assertThat(registry.node(22L)).isSameAs(machine);
        assertThat(registry.auth(legacy)).isNull();
        assertThat(registry.auth(machine).nodeIds()).containsExactly(11L, 22L);
        verify(devices, never()).clearNode(22L);
        verify(legacy).close(any(CloseStatus.class));
    }

    @Test
    void redisCleanupFailureCannotLeaveConnectionOrOtherNodesRegistered()
        throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        doThrow(new IllegalStateException("redis unavailable"))
            .when(devices).clearNode(11L);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        WebSocketSession session = session("machine-9");
        registry.register(
            session,
            new NodeWebSocketAuthContext(9L, "token", null, List.of(11L, 12L))
        );

        assertThatCode(() -> registry.unregister(session)).doesNotThrowAnyException();

        assertThat(registry.machine(9L)).isNull();
        assertThat(registry.node(11L)).isNull();
        assertThat(registry.node(12L)).isNull();
        assertThat(registry.auth(session)).isNull();
        assertThat(registry.sessions()).isEmpty();
        verify(devices).clearNode(11L);
        verify(devices).clearNode(12L);
    }

    @Test
    void explicitMachineDisconnectDetachesMappingsBeforeClosing() throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        WebSocketSession session = session("machine-15");
        registry.register(
            session,
            new NodeWebSocketAuthContext(15L, "token", null, List.of(41L))
        );
        CloseStatus status = CloseStatus.POLICY_VIOLATION.withReason("revoked");

        assertThat(registry.disconnectMachine(15L, status)).isTrue();

        assertThat(registry.machine(15L)).isNull();
        assertThat(registry.node(41L)).isNull();
        assertThat(registry.auth(session)).isNull();
        verify(devices).clearNode(41L);
        verify(session).close(status);
    }

    @Test
    void legacyNodeDisconnectNeverClosesMultiNodeMachineSocket() throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        WebSocketSession session = session("machine-16");
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            16L, "token", null, List.of(51L, 52L)
        );
        registry.register(session, auth);

        boolean disconnected = registry.disconnectLegacyNode(
            51L,
            CloseStatus.POLICY_VIOLATION.withReason("node removed")
        );

        assertThat(disconnected).isFalse();
        assertThat(registry.machine(16L)).isSameAs(session);
        assertThat(registry.node(51L)).isSameAs(session);
        assertThat(registry.auth(session)).isEqualTo(auth);
        verify(session, never()).close(any(CloseStatus.class));
    }

    private WebSocketSession session(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
