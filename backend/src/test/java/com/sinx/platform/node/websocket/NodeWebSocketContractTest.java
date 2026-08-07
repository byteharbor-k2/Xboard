package com.sinx.platform.node.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;

import com.sinx.platform.node.application.NodeDeviceStateService;
import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;

import tools.jackson.databind.ObjectMapper;

class NodeWebSocketContractTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-06T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void handshakeAuthenticatesMachineFromXboardNodeQueryParameters() {
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            7L, "node-token", null, List.of(21L, 22L)
        );
        when(authenticator.machine(7L, "node-token")).thenReturn(auth);
        NodeWebSocketHandshakeInterceptor interceptor =
            new NodeWebSocketHandshakeInterceptor(authenticator);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest(
            "GET", "/ws"
        );
        servletRequest.setQueryString("machine_id=7&token=node-token");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(WebSocketHandler.class),
            attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            auth
        );
        verify(authenticator).machine(7L, "node-token");
    }

    @Test
    void legacyWebSocketAuthenticationUsesGlobalTokenAndNeedsNoMachine() {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        ProxyNode node = mock(ProxyNode.class);
        when(node.getId()).thenReturn(17L);
        when(protocol.authenticateLegacy(17L, "global-token")).thenReturn(
            new NodeProtocolService.AuthenticatedNode(null, node)
        );
        NodeWebSocketAuthenticator authenticator = new NodeWebSocketAuthenticator(
            mock(NodeMachineService.class),
            protocol,
            mock(ProxyNodeRepository.class)
        );

        NodeWebSocketAuthContext auth = authenticator.node(17L, "global-token");

        assertThat(auth.machineMode()).isFalse();
        assertThat(auth.machineId()).isZero();
        assertThat(auth.nodeId()).isEqualTo(17L);
        assertThat(auth.nodeIds()).containsExactly(17L);
        verify(protocol).authenticateLegacy(17L, "global-token");
    }

    @Test
    void handshakeAcceptsLegacyTokenAndNodeIdQueryParameters() {
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            0, "global-token", 17L, List.of(17L)
        );
        when(authenticator.node(17L, "global-token")).thenReturn(auth);
        NodeWebSocketHandshakeInterceptor interceptor =
            new NodeWebSocketHandshakeInterceptor(authenticator);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
        servletRequest.setQueryString("token=global-token&node_id=17");
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
            new ServletServerHttpRequest(servletRequest),
            new ServletServerHttpResponse(new MockHttpServletResponse()),
            mock(WebSocketHandler.class),
            attributes
        );

        assertThat(accepted).isTrue();
        assertThat(attributes).containsEntry(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            auth
        );
        verify(authenticator).node(17L, "global-token");
    }

    @Test
    void legacyConnectionUsesLegacyConfigUsersAndReportPaths() throws Exception {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            0, "global-token", 17L, List.of(17L)
        );
        when(authenticator.refresh(auth)).thenReturn(auth);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        ObjectMapper objectMapper = new ObjectMapper();
        NodeWebSocketHandler handler = new NodeWebSocketHandler(
            registry,
            authenticator,
            new NodeWebSocketSyncService(
                registry,
                protocol,
                mock(NodeManagementService.class),
                objectMapper,
                CLOCK
            ),
            protocol,
            devices,
            objectMapper,
            CLOCK
        );
        WebSocketSession session = session("legacy-node-17");
        session.getAttributes().put(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            auth
        );
        when(protocol.configLegacy(17L, "global-token")).thenReturn(
            new NodeProtocolService.ConfigPayload(Map.of("protocol", "vless"), "\"c\"")
        );
        when(protocol.usersLegacy(17L, "global-token")).thenReturn(
            new NodeProtocolService.UsersPayload(List.of(), "\"u\"")
        );

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
            {"event":"node.status","data":{"cpu":7}}
            """));

        assertThat(sentMessages(session).get(0))
            .contains("\"event\":\"auth.success\"")
            .contains("\"node_id\":17");
        verify(protocol).configLegacy(17L, "global-token");
        verify(protocol).usersLegacy(17L, "global-token");
        verify(protocol).reportLegacy(
            17L,
            "global-token",
            Map.of("metrics", Map.of("cpu", 7))
        );
    }

    @Test
    void machineConnectionAuthenticatesAndSupportsFullSyncStatusAndDevices() throws Exception {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            3L, "secret", null, List.of(11L)
        );
        when(authenticator.refresh(auth)).thenReturn(auth);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        ObjectMapper objectMapper = new ObjectMapper();
        NodeWebSocketSyncService sync = new NodeWebSocketSyncService(
            registry,
            protocol,
            mock(NodeManagementService.class),
            objectMapper,
            CLOCK
        );
        NodeWebSocketHandler handler = new NodeWebSocketHandler(
            registry,
            authenticator,
            sync,
            protocol,
            devices,
            objectMapper,
            CLOCK
        );
        WebSocketSession session = session("machine-3");
        session.getAttributes().put(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            auth
        );
        when(protocol.config(3L, 11L, "secret")).thenReturn(
            new NodeProtocolService.ConfigPayload(
                Map.of("node_id", 11, "protocol", "vless"),
                "\"config\""
            )
        );
        when(protocol.users(3L, 11L, "secret")).thenReturn(
            new NodeProtocolService.UsersPayload(
                List.of(Map.of("id", 91L, "uuid", "user-91")),
                "\"users\""
            )
        );
        when(devices.snapshotForUsers(eq(java.util.Set.of(91L)), any()))
            .thenReturn(Map.of(91L, List.of("203.0.113.9")));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
            {"event":"node.status","data":{"node_id":11,"cpu":7,"active_connections":3}}
            """));
        handler.handleTextMessage(session, new TextMessage("""
            {"event":"report.devices","data":{"node_id":11,"devices":{"91":["203.0.113.9:443"]}}}
            """));
        handler.handleTextMessage(session, new TextMessage("""
            {"event":"request.devices","data":{"node_id":11}}
            """));

        List<String> messages = sentMessages(session);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).contains("\"event\":\"auth.success\"")
            .contains("\"machine_id\":3")
            .contains("\"node_ids\":[11]");
        assertThat(messages.get(1)).contains("\"event\":\"sync.config\"")
            .contains("\"node_id\":11");
        assertThat(messages.get(2)).contains("\"event\":\"sync.users\"")
            .contains("\"id\":91");
        assertThat(messages.get(3)).contains("\"event\":\"sync.devices\"")
            .contains("203.0.113.9");
        verify(protocol).report(
            eq(3L), eq(11L), eq("secret"),
            eq(Map.of("metrics", Map.of("cpu", 7, "active_connections", 3)))
        );
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<String>>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(devices).replaceSnapshot(eq(11L), snapshot.capture(), eq(CLOCK.instant()));
        assertThat(snapshot.getValue()).containsEntry(91L, List.of("203.0.113.9:443"));
    }

    @Test
    void machineConnectionRejectsEventsForUnassignedNode() throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeWebSocketAuthContext auth = new NodeWebSocketAuthContext(
            4L, "secret", null, List.of(12L)
        );
        when(authenticator.refresh(auth)).thenReturn(auth);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NodeWebSocketHandler handler = new NodeWebSocketHandler(
            registry,
            authenticator,
            new NodeWebSocketSyncService(
                registry,
                protocol,
                mock(NodeManagementService.class),
                objectMapper,
                CLOCK
            ),
            protocol,
            devices,
            objectMapper,
            CLOCK
        );
        WebSocketSession session = session("machine-4");
        session.getAttributes().put(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            auth
        );
        when(protocol.config(4L, 12L, "secret")).thenReturn(
            new NodeProtocolService.ConfigPayload(Map.of("protocol", "vless"), "\"c\"")
        );
        when(protocol.users(4L, 12L, "secret")).thenReturn(
            new NodeProtocolService.UsersPayload(List.of(), "\"u\"")
        );
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
            {"event":"node.status","data":{"node_id":999}}
            """));

        verify(session).close(any());
    }

    @Test
    void connectionRevalidatesCredentialsBeforeReplacingExistingSession() throws Exception {
        NodeDeviceStateService devices = mock(NodeDeviceStateService.class);
        NodeWebSocketRegistry registry = new NodeWebSocketRegistry(devices);
        NodeWebSocketAuthenticator authenticator = mock(NodeWebSocketAuthenticator.class);
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NodeWebSocketHandler handler = new NodeWebSocketHandler(
            registry,
            authenticator,
            new NodeWebSocketSyncService(
                registry,
                protocol,
                mock(NodeManagementService.class),
                objectMapper,
                CLOCK
            ),
            protocol,
            devices,
            objectMapper,
            CLOCK
        );
        NodeWebSocketAuthContext valid = new NodeWebSocketAuthContext(
            8L, "current-token", null, List.of(31L)
        );
        WebSocketSession existing = session("machine-8-current");
        registry.register(existing, valid);

        NodeWebSocketAuthContext stale = new NodeWebSocketAuthContext(
            8L, "revoked-token", null, List.of(31L)
        );
        when(authenticator.refresh(stale)).thenThrow(
            new IllegalArgumentException("revoked")
        );
        WebSocketSession rejected = session("machine-8-stale");
        rejected.getAttributes().put(
            NodeWebSocketHandshakeInterceptor.AUTH_CONTEXT,
            stale
        );

        handler.afterConnectionEstablished(rejected);

        assertThat(registry.machine(8L)).isSameAs(existing);
        assertThat(registry.auth(existing)).isEqualTo(valid);
        assertThat(registry.auth(rejected)).isNull();
        assertThat(sentMessages(rejected)).isEmpty();
        verify(rejected).close(any());
    }

    @SuppressWarnings("unchecked")
    private WebSocketSession session(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        List<String> sent = new ArrayList<>();
        when(session.getId()).thenReturn(id);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            sent.add(String.valueOf(message.getPayload()));
            return null;
        }).when(session).sendMessage(any());
        attributes.put("sent", sent);
        return session;
    }

    @SuppressWarnings("unchecked")
    private List<String> sentMessages(WebSocketSession session) {
        return (List<String>) session.getAttributes().get("sent");
    }
}
