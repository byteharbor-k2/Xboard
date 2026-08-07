package com.sinx.platform.node.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeRouteRuleService;
import com.sinx.platform.node.websocket.NodeWebSocketChangeNotifier;

@RestController
@RequestMapping("/api/v2/admin/server/route")
public class AdminNodeRouteRuleController {

    private final NodeRouteRuleService routes;
    private final NodeManagementService nodes;
    private final NodeWebSocketChangeNotifier notifier;

    public AdminNodeRouteRuleController(
        NodeRouteRuleService routes,
        NodeManagementService nodes,
        NodeWebSocketChangeNotifier notifier
    ) {
        this.routes = routes;
        this.nodes = nodes;
        this.notifier = notifier;
    }

    @GetMapping("/fetch")
    XboardResponse<List<NodeRouteRuleService.RouteView>> fetch() {
        return XboardResponse.of(routes.list());
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(@RequestBody SaveRouteRequest request) {
        List<NodeManagementService.NodeView> affected = request.id() == null
            ? List.of()
            : nodes.list();
        boolean saved = routes.save(
            request.id(),
            request.remarks(),
            request.matches(),
            request.action(),
            request.actionValue()
        );
        if (request.id() != null) {
            notifier.routeChanged(request.id(), affected);
        }
        return XboardResponse.of(saved);
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        List<NodeManagementService.NodeView> affected = nodes.list();
        boolean deleted = routes.delete(request.id());
        notifier.routeChanged(request.id(), affected);
        return XboardResponse.of(deleted);
    }

    record SaveRouteRequest(
        Long id,
        String remarks,
        @JsonProperty("match") List<String> matches,
        String action,
        @JsonProperty("action_value") String actionValue
    ) {
    }

    record IdRequest(long id) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) { return new XboardResponse<>(data); }
    }
}
