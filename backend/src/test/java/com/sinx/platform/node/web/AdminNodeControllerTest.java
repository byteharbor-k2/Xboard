package com.sinx.platform.node.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.websocket.NodeWebSocketChangeNotifier;

class AdminNodeControllerTest {

    @Test
    void acceptsBatchUpdatesWithoutMachineFields() throws Exception {
        NodeManagementService nodes = mock(NodeManagementService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        NodeManagementService.NodeView before = node(true);
        NodeManagementService.NodeView after = node(false);
        when(nodes.get(1L)).thenReturn(before, after);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminNodeController(
            nodes,
            notifier
        )).build();

        mvc.perform(post("/api/v2/admin/server/manage/batchUpdate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"ids":[1],"show":false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(nodes).batchUpdate(List.of(1L), false, null, null, false);
        verify(notifier).nodesUpdated(List.of(before), List.of(after));
    }

    private NodeManagementService.NodeView node(boolean show) {
        return new NodeManagementService.NodeView(
            1, "vless", "node-1", null, 2L, List.of(3L), List.of(),
            "Node", BigDecimal.ONE, false, List.of(), 0, 0, 0,
            List.of(), "node.example.test", 443, 8443,
            Map.of("network", "tcp", "tls", 1), List.of(), List.of(), null,
            show, true, 0, 0, 0, null, null, null, null, 100, 200
        );
    }
}
