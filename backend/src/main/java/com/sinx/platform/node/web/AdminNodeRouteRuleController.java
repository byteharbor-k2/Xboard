package com.sinx.platform.node.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.application.NodeRouteRuleService;

@RestController
@RequestMapping("/api/v2/admin/server/route")
public class AdminNodeRouteRuleController {

    private final NodeRouteRuleService routes;

    public AdminNodeRouteRuleController(NodeRouteRuleService routes) {
        this.routes = routes;
    }

    @GetMapping("/fetch")
    XboardResponse<List<NodeRouteRuleService.RouteView>> fetch() {
        return XboardResponse.of(routes.list());
    }

    @PostMapping("/save")
    XboardResponse<Boolean> save(@RequestBody SaveRouteRequest request) {
        return XboardResponse.of(routes.save(
            request.id(),
            request.remarks(),
            request.matches(),
            request.action(),
            request.actionValue()
        ));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        return XboardResponse.of(routes.delete(request.id()));
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
