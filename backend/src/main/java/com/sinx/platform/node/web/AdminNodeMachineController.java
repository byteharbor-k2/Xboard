package com.sinx.platform.node.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.websocket.NodeWebSocketChangeNotifier;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v2/admin/server/machine")
public class AdminNodeMachineController {

    private static final String INSTALLER_URL =
        "https://raw.githubusercontent.com/cedar2025/xboard-node/dev/install.sh";

    private final NodeMachineService machines;
    private final NodeManagementService nodes;
    private final PlatformConfigurationService configuration;
    private final NodeWebSocketChangeNotifier notifier;

    public AdminNodeMachineController(
        NodeMachineService machines,
        NodeManagementService nodes,
        PlatformConfigurationService configuration,
        NodeWebSocketChangeNotifier notifier
    ) {
        this.machines = machines;
        this.nodes = nodes;
        this.configuration = configuration;
        this.notifier = notifier;
    }

    @GetMapping("/fetch")
    XboardResponse<List<MachineView>> fetch() {
        return XboardResponse.of(machines.list().stream()
            .map(MachineView::from)
            .toList());
    }

    @PostMapping("/save")
    XboardResponse<?> save(
        @RequestBody SaveMachineRequest request,
        HttpServletRequest servletRequest
    ) {
        if (request.id() != null) {
            machines.update(
                request.id(),
                request.name(),
                request.notes(),
                request.active()
            );
            if (Boolean.FALSE.equals(request.active())) {
                notifier.machineRevoked(request.id());
            }
            return XboardResponse.of(true);
        }
        NodeMachineService.CreatedMachine created = machines.create(
            request.name(),
            request.notes(),
            request.active() == null || request.active()
        );
        return XboardResponse.of(new CreatedMachineView(
            created.id(),
            created.token(),
            installCommand(servletRequest, created.id(), created.token())
        ));
    }

    @PostMapping("/resetToken")
    XboardResponse<Map<String, String>> resetToken(
        @RequestBody MachineIdRequest request
    ) {
        String token = machines.rotateToken(request.id());
        notifier.machineRevoked(request.id());
        return XboardResponse.of(Map.of("token", token));
    }

    @GetMapping("/getToken")
    XboardResponse<Map<String, String>> getToken(@RequestParam long id) {
        return XboardResponse.of(Map.of("token", machines.token(id)));
    }

    @GetMapping("/installCommand")
    XboardResponse<Map<String, String>> installCommand(
        @RequestParam long id,
        HttpServletRequest request
    ) {
        return XboardResponse.of(Map.of(
            "command",
            installCommand(request, id, machines.token(id))
        ));
    }

    @PostMapping("/drop")
    XboardResponse<Boolean> drop(@RequestBody MachineIdRequest request) {
        machines.delete(request.id());
        notifier.machineDeleted(request.id());
        return XboardResponse.of(true);
    }

    @GetMapping("/nodes")
    XboardResponse<List<NodeManagementService.NodeView>> nodes(
        @RequestParam(name = "machine_id") long machineId
    ) {
        machines.token(machineId);
        return XboardResponse.of(nodes.forMachine(machineId, false));
    }

    @GetMapping("/history")
    XboardResponse<List<LoadHistoryView>> history(
        @RequestParam(name = "machine_id") long machineId,
        @RequestParam(defaultValue = "60") int limit,
        @RequestParam(name = "range_hours", required = false) Integer rangeHours
    ) {
        return XboardResponse.of(machines.history(machineId, limit, rangeHours).stream()
            .map(LoadHistoryView::from)
            .toList());
    }

    private String installCommand(
        HttpServletRequest request,
        long machineId,
        String token
    ) {
        String panelUrl = configuration.appUrl()
            .map(this::withoutTrailingSlash)
            .orElseGet(() -> requestOrigin(request));
        return "curl -fsSL " + INSTALLER_URL
            + " | sudo bash -s -- --mode machine --panel " + shellQuote(panelUrl)
            + " --token " + shellQuote(token) + " --machine-id " + machineId;
    }

    private String requestOrigin(HttpServletRequest request) {
        String panelUrl = request.getScheme() + "://" + request.getServerName();
        if (!(("http".equals(request.getScheme()) && request.getServerPort() == 80)
            || ("https".equals(request.getScheme()) && request.getServerPort() == 443))) {
            panelUrl += ":" + request.getServerPort();
        }
        return panelUrl;
    }

    private String withoutTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    record SaveMachineRequest(
        Long id,
        String name,
        String notes,
        @JsonProperty("is_active") Boolean active
    ) {
    }

    record MachineIdRequest(long id) {
    }

    record CreatedMachineView(
        long id,
        String token,
        @JsonProperty("install_command") String installCommand
    ) {
    }

    record MachineView(
        long id,
        String name,
        String notes,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("last_seen_at") Long lastSeenAt,
        @JsonProperty("load_status") Object loadStatus,
        @JsonProperty("servers_count") long serversCount,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) {
        static MachineView from(NodeMachineService.MachineView view) {
            return new MachineView(
                view.id(), view.name(), view.notes(), view.active(),
                view.lastSeenAt(), view.loadStatus(), view.serversCount(),
                view.createdAt().getEpochSecond(), view.updatedAt().getEpochSecond()
            );
        }
    }

    record LoadHistoryView(
        double cpu,
        @JsonProperty("mem_total") long memoryTotal,
        @JsonProperty("mem_used") long memoryUsed,
        @JsonProperty("disk_total") long diskTotal,
        @JsonProperty("disk_used") long diskUsed,
        @JsonProperty("net_in_speed") Double networkInSpeed,
        @JsonProperty("net_out_speed") Double networkOutSpeed,
        @JsonProperty("recorded_at") long recordedAt
    ) {
        static LoadHistoryView from(NodeMachineService.LoadHistoryView view) {
            return new LoadHistoryView(
                view.cpu(), view.memoryTotal(), view.memoryUsed(),
                view.diskTotal(), view.diskUsed(), view.networkInSpeed(),
                view.networkOutSpeed(), view.recordedAt()
            );
        }
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
