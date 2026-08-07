package com.sinx.platform.node.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.sinx.platform.node.application.NodeAccessGroupService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeRouteRuleService;
import com.sinx.platform.node.websocket.NodeWebSocketChangeNotifier;

class AdminNodeGroupRouteControllerTest {

    @Test
    void exposesTheOriginalXboardGroupEndpointsAndResponseFields() throws Exception {
        NodeAccessGroupService groups = mock(NodeAccessGroupService.class);
        when(groups.list()).thenReturn(List.of(
            new NodeAccessGroupService.GroupView(3, "Premium", 4, 5, 100, 200)
        ));
        when(groups.save(null, "Premium")).thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeAccessGroupController(groups)
        ).build();

        mvc.perform(get("/api/v2/admin/server/group/fetch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(3))
            .andExpect(jsonPath("$.data[0].users_count").value(4))
            .andExpect(jsonPath("$.data[0].server_count").value(5));

        mvc.perform(post("/api/v2/admin/server/group/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"name":"Premium"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(groups).save(null, "Premium");
    }

    @Test
    void exposesTheOriginalXboardRouteEndpointsAndResponseFields() throws Exception {
        NodeRouteRuleService routes = mock(NodeRouteRuleService.class);
        NodeManagementService nodes = mock(NodeManagementService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        when(routes.list()).thenReturn(List.of(
            new NodeRouteRuleService.RouteView(
                7, "Block ads", List.of("domain:ads.example"),
                "block", null, 100, 200
            )
        ));
        when(routes.save(null, "Block ads", List.of("domain:ads.example"), "block", null))
            .thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeRouteRuleController(routes, nodes, notifier)
        ).build();

        mvc.perform(get("/api/v2/admin/server/route/fetch"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].id").value(7))
            .andExpect(jsonPath("$.data[0].match[0]").value("domain:ads.example"))
            .andExpect(jsonPath("$.data[0].action").value("block"));

        mvc.perform(post("/api/v2/admin/server/route/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "remarks":"Block ads",
                      "match":["domain:ads.example"],
                      "action":"block",
                      "action_value":null
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));
        verify(routes).save(
            null, "Block ads", List.of("domain:ads.example"), "block", null
        );
    }

    @Test
    void updatingRoutePushesConfigsToNodesThatReferenceIt() throws Exception {
        NodeRouteRuleService routes = mock(NodeRouteRuleService.class);
        NodeManagementService nodes = mock(NodeManagementService.class);
        NodeWebSocketChangeNotifier notifier = mock(NodeWebSocketChangeNotifier.class);
        NodeManagementService.NodeView node = node();
        when(nodes.list()).thenReturn(List.of(node));
        when(routes.save(7L, "Direct", List.of("domain:example.test"), "direct", null))
            .thenReturn(true);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new AdminNodeRouteRuleController(routes, nodes, notifier)
        ).build();

        mvc.perform(post("/api/v2/admin/server/route/save")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "id":7,
                      "remarks":"Direct",
                      "match":["domain:example.test"],
                      "action":"direct"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").value(true));

        verify(notifier).routeChanged(7L, List.of(node));
    }

    private NodeManagementService.NodeView node() {
        return new NodeManagementService.NodeView(
            1, "vless", "node-1", null, 2L, List.of(3L), List.of(7L),
            "Node", BigDecimal.ONE, false, List.of(), 0, 0, 0,
            List.of(), "node.example.test", 443, 8443,
            Map.of("network", "tcp", "tls", 1), List.of(), List.of(), null,
            true, true, 0, 0, 0, null, null, null, null, 100, 200
        );
    }
}
