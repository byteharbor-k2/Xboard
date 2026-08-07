package com.sinx.platform.node.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.shared.web.ApiProblemException;

class LegacyUniProxyControllerTest {

    @Test
    void configSupportsLegacyShapeAndConditionalRequests() throws Exception {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        when(protocol.configLegacy(7L, "global-token")).thenReturn(
            new NodeProtocolService.ConfigPayload(
                Map.of("protocol", "vless", "server_port", 443),
                "\"config-etag\""
            )
        );
        MockMvc mvc = mvc(protocol);

        mvc.perform(get("/api/v1/server/UniProxy/config")
                .param("node_id", "7")
                .param("node_type", "vless")
                .param("token", "global-token"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ETAG, "\"config-etag\""))
            .andExpect(jsonPath("$.protocol").value("vless"))
            .andExpect(jsonPath("$.server_port").value(443));

        mvc.perform(get("/api/v1/server/UniProxy/config")
                .param("node_id", "7")
                .param("token", "global-token")
                .header(HttpHeaders.IF_NONE_MATCH, "\"config-etag\""))
            .andExpect(status().isNotModified());
    }

    @Test
    void userEndpointReturnsTheLegacyUsersEnvelope() throws Exception {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        when(protocol.usersLegacy(7L, "global-token")).thenReturn(
            new NodeProtocolService.UsersPayload(
                List.of(Map.of(
                    "id", 101L,
                    "uuid", "00000000-0000-0000-0000-000000000101",
                    "speed_limit", 50,
                    "device_limit", 3
                )),
                "\"users-etag\""
            )
        );

        mvc(protocol).perform(get("/api/v1/server/UniProxy/user")
                .param("node_id", "7")
                .param("token", "global-token"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ETAG, "\"users-etag\""))
            .andExpect(jsonPath("$.users[0].id").value(101))
            .andExpect(jsonPath("$.users[0].device_limit").value(3));
    }

    @Test
    void trafficAndAliveReportsRemoveAuthenticationFields() {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        LegacyUniProxyController controller = new LegacyUniProxyController(protocol);

        controller.push(Map.of(
            "node_id", 7,
            "node_type", "vless",
            "token", "global-token",
            "101", List.of(4, 8)
        ));
        controller.alive(Map.of(
            "node_id", "7",
            "node_type", "vless",
            "token", "global-token",
            "101", List.of("198.51.100.1:443")
        ));

        verify(protocol).reportLegacy(
            7L,
            "global-token",
            Map.of("traffic", Map.of("101", List.of(4, 8)))
        );
        verify(protocol).reportLegacy(
            7L,
            "global-token",
            Map.of("alive", Map.of("101", List.of("198.51.100.1:443")))
        );
    }

    @Test
    void aliveListReturnsCrossNodeDeviceCounts() {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        when(protocol.aliveListLegacy(7L, "global-token"))
            .thenReturn(Map.of(101L, 2));

        Map<String, Object> response = new LegacyUniProxyController(protocol)
            .aliveList(7L, "global-token");

        assertThat(response).containsEntry("alive", Map.of(101L, 2));
    }

    @Test
    void statusKeepsTheLegacyStatusEnvelope() {
        NodeProtocolService protocol = mock(NodeProtocolService.class);
        LegacyUniProxyController controller = new LegacyUniProxyController(protocol);

        Map<String, Object> response = controller.status(
            new LegacyUniProxyController.LegacyStatusRequest(
                7L,
                "global-token",
                "vless",
                25.5,
                new LegacyUniProxyController.ResourceUsage(1024, 256),
                new LegacyUniProxyController.ResourceUsage(512, 64),
                new LegacyUniProxyController.ResourceUsage(2048, 1024)
            )
        );

        verify(protocol).reportLegacy(7L, "global-token", Map.of(
            "status", Map.of(
                "cpu", 25.5,
                "mem", Map.of("total", 1024L, "used", 256L),
                "swap", Map.of("total", 512L, "used", 64L),
                "disk", Map.of("total", 2048L, "used", 1024L)
            )
        ));
        assertThat(response)
            .containsEntry("data", true)
            .containsEntry("code", 0)
            .containsEntry("message", "success");
    }

    @Test
    void malformedLegacyReportIsRejectedBeforeAuthentication() {
        LegacyUniProxyController controller = new LegacyUniProxyController(
            mock(NodeProtocolService.class)
        );

        assertThatThrownBy(() -> controller.push(Map.of("node_id", 7)))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("INVALID_LEGACY_NODE_REQUEST");
    }

    private MockMvc mvc(NodeProtocolService protocol) {
        return MockMvcBuilders.standaloneSetup(
            new LegacyUniProxyController(protocol)
        ).build();
    }
}
