package com.sinx.platform.node.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.application.NodeManagementService;

@RestController
@RequestMapping("/api/v2/admin/server/manage")
public class AdminNodeController {

    private final NodeManagementService nodes;

    public AdminNodeController(NodeManagementService nodes) {
        this.nodes = nodes;
    }

    @GetMapping("/getNodes")
    XboardResponse<List<NodeManagementService.NodeView>> getNodes() {
        return XboardResponse.of(nodes.list());
    }

    @PostMapping("/save")
    XboardResponse<NodeManagementService.NodeView> save(@RequestBody SaveNodeRequest request) {
        return XboardResponse.of(nodes.save(request.toDraft()));
    }

    @PostMapping("/update")
    XboardResponse<Boolean> update(@RequestBody Map<String, Object> request) {
        long id = ((Number) request.get("id")).longValue();
        nodes.quickUpdate(
            id,
            bool(request.get("show")),
            bool(request.get("enabled")),
            longValue(request.get("machine_id")),
            request.containsKey("machine_id")
        );
        return XboardResponse.of(true);
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody IdRequest request) {
        nodes.delete(request.id());
        return XboardResponse.of(true);
    }

    @PostMapping("/copy")
    XboardResponse<NodeManagementService.NodeView> copy(@RequestBody IdRequest request) {
        return XboardResponse.of(nodes.copy(request.id()));
    }

    @PostMapping("/sort")
    XboardResponse<Boolean> sort(@RequestBody List<SortRequest> request) {
        nodes.sort(request.stream().map(item -> new NodeManagementService.SortItem(item.id(), item.order())).toList());
        return XboardResponse.of(true);
    }

    @PostMapping("/batchDelete")
    XboardResponse<Boolean> batchDelete(@RequestBody IdsRequest request) {
        nodes.batchDelete(request.ids());
        return XboardResponse.of(true);
    }

    @PostMapping("/batchUpdate")
    XboardResponse<Boolean> batchUpdate(@RequestBody BatchUpdateRequest request) {
        nodes.batchUpdate(request.ids(), request.show(), request.enabled(), request.machineId(), request.updateMachine());
        return XboardResponse.of(true);
    }

    @PostMapping("/resetTraffic")
    XboardResponse<Boolean> resetTraffic(@RequestBody IdRequest request) {
        nodes.resetTraffic(List.of(request.id()));
        return XboardResponse.of(true);
    }

    @PostMapping("/batchResetTraffic")
    XboardResponse<Boolean> batchResetTraffic(@RequestBody IdsRequest request) {
        nodes.resetTraffic(request.ids());
        return XboardResponse.of(true);
    }

    private static Boolean bool(Object value) { return value instanceof Boolean booleanValue ? booleanValue : null; }
    private static Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }

    record IdRequest(long id) {}
    record IdsRequest(List<Long> ids) {}
    record SortRequest(long id, int order) {}
    record BatchUpdateRequest(
        List<Long> ids, Boolean show, Boolean enabled,
        @JsonProperty("machine_id") Long machineId,
        @JsonProperty("update_machine") boolean updateMachine
    ) {}

    record SaveNodeRequest(
        Long id,
        String type,
        String code,
        @JsonProperty("parent_id") Long parentId,
        @JsonProperty("machine_id") Long machineId,
        @JsonProperty("group_ids") List<Long> groupIds,
        @JsonProperty("route_ids") List<Long> routeIds,
        String name,
        BigDecimal rate,
        @JsonProperty("rate_time_enable") Boolean rateTimeEnable,
        @JsonProperty("rate_time_ranges") Object rateTimeRanges,
        @JsonProperty("transfer_enable") Long transferEnable,
        List<String> tags,
        String host,
        Integer port,
        @JsonProperty("server_port") Integer serverPort,
        @JsonProperty("protocol_settings") Map<String, Object> protocolSettings,
        @JsonProperty("custom_outbounds") Object customOutbounds,
        @JsonProperty("custom_routes") Object customRoutes,
        @JsonProperty("cert_config") Object certConfig,
        Boolean show,
        Boolean enabled,
        Integer sort
    ) {
        NodeManagementService.NodeDraft toDraft() {
            return new NodeManagementService.NodeDraft(
                id, type, code, parentId, machineId, groupIds, routeIds, name, rate,
                rateTimeEnable, rateTimeRanges, transferEnable, tags, host, port,
                serverPort, protocolSettings, customOutbounds, customRoutes,
                certConfig, show, enabled, sort
            );
        }
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) { return new XboardResponse<>(data); }
    }
}
