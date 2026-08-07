package com.sinx.platform.node.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.node.websocket.NodeWebSocketEndpointInfo;

class NodeMachineProtocolControllerTest {

    @Test
    void handshakeAdvertisesWebSocketAndKeepsPollingFallback() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        NodeWebSocketEndpointInfo webSocket = mock(NodeWebSocketEndpointInfo.class);
        when(webSocket.endpoint(org.mockito.ArgumentMatchers.any())).thenReturn(
            new NodeWebSocketEndpointInfo.Endpoint(true, "wss://panel.example/ws", 15, 30)
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NodeMachineProtocolController(
                machines,
                mock(NodeManagementService.class),
                protocol,
                webSocket,
                mock(PlatformConfigurationService.class)
            )
        ).build();

        mvc.perform(post("/api/v2/server/handshake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":2,"token":"token"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.websocket.enabled").value(true))
            .andExpect(jsonPath("$.websocket.ws_url").value("wss://panel.example/ws"))
            .andExpect(jsonPath("$.settings.push_interval").value(15))
            .andExpect(jsonPath("$.settings.pull_interval").value(30));
        verify(machines).authenticate(2L, "token");
    }

    @Test
    void legacyHandshakeAcceptsGlobalTokenWithoutNodeId() throws Exception {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        NodeWebSocketEndpointInfo webSocket = mock(NodeWebSocketEndpointInfo.class);
        when(webSocket.endpoint(org.mockito.ArgumentMatchers.any())).thenReturn(
            new NodeWebSocketEndpointInfo.Endpoint(false, null, 21, 34)
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NodeMachineProtocolController(
                mock(NodeMachineService.class),
                mock(NodeManagementService.class),
                protocol,
                webSocket,
                mock(PlatformConfigurationService.class)
            )
        ).build();

        mvc.perform(post("/api/v2/server/handshake")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"token":"global-token"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.websocket.enabled").value(false))
            .andExpect(jsonPath("$.settings.push_interval").value(21))
            .andExpect(jsonPath("$.settings.pull_interval").value(34));

        verify(protocol).authenticateLegacyToken("global-token");
    }

    @Test
    void acceptsStatusWithoutOptionalSwapDiskAndNetworkMetrics() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NodeMachineProtocolController(
                machines,
                mock(NodeManagementService.class),
                mock(NodeProtocolService.class),
                mock(NodeWebSocketEndpointInfo.class),
                mock(PlatformConfigurationService.class)
            )
        ).build();

        mvc.perform(post("/api/v2/server/machine/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "machine_id": 2,
                      "token": "token",
                      "cpu": 12.5,
                      "mem": {"total": 1024, "used": 256}
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        ArgumentCaptor<NodeMachineService.MachineStatus> status =
            ArgumentCaptor.forClass(NodeMachineService.MachineStatus.class);
        verify(machines).recordStatus(
            org.mockito.ArgumentMatchers.eq(2L),
            org.mockito.ArgumentMatchers.eq("token"),
            status.capture()
        );
        assertThat(status.getValue().swap()).isNull();
        assertThat(status.getValue().disk()).isNull();
        assertThat(status.getValue().net()).isNull();
    }

    @Test
    void machineNodeListUsesConfiguredPollingIntervals() throws Exception {
        NodeMachineService machines = mock(NodeMachineService.class);
        NodeManagementService nodes = mock(NodeManagementService.class);
        PlatformConfigurationService configuration =
            mock(PlatformConfigurationService.class);
        when(nodes.forMachine(2L, true)).thenReturn(java.util.List.of());
        when(configuration.nodeCommunicationSettings()).thenReturn(
            new PlatformConfigurationService.NodeCommunicationSettings(
                "legacy-token", 31, 17, true, null
            )
        );
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new NodeMachineProtocolController(
                machines,
                nodes,
                mock(NodeProtocolService.class),
                mock(NodeWebSocketEndpointInfo.class),
                configuration
            )
        ).build();

        mvc.perform(post("/api/v2/server/machine/nodes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"machine_id":2,"token":"token"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.nodes").isArray())
            .andExpect(jsonPath("$.base_config.push_interval").value(17))
            .andExpect(jsonPath("$.base_config.pull_interval").value(31));

        verify(machines).authenticate(2L, "token");
    }
}
