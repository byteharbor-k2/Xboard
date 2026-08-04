package com.sinx.platform.node.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.application.NodeMachineService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/v2/server")
public class NodeMachineProtocolController {

    private final NodeMachineService machines;

    public NodeMachineProtocolController(NodeMachineService machines) {
        this.machines = machines;
    }

    @PostMapping("/handshake")
    HandshakeResponse handshake(@Valid @RequestBody MachineCredentials request) {
        machines.authenticate(request.machineId(), request.token());
        return new HandshakeResponse(
            new WebSocketSettings(false, null),
            new PollSettings(60, 60)
        );
    }

    @PostMapping("/machine/nodes")
    MachineNodesResponse nodes(@Valid @RequestBody MachineCredentials request) {
        machines.authenticate(request.machineId(), request.token());
        return new MachineNodesResponse(List.of(), new PollSettings(60, 60));
    }

    @PostMapping("/machine/status")
    XboardDataResponse status(@Valid @RequestBody MachineStatusRequest request) {
        machines.recordStatus(
            request.machineId(),
            request.token(),
            new NodeMachineService.MachineStatus(
                request.cpu(),
                request.mem().toService(),
                request.swap().toService(),
                request.disk().toService(),
                request.net() == null ? null : request.net().toService()
            )
        );
        return new XboardDataResponse(true);
    }

    record MachineCredentials(
        @JsonProperty("machine_id") @NotNull @Positive Long machineId,
        @NotBlank String token
    ) {
    }

    record MachineStatusRequest(
        @JsonProperty("machine_id") @NotNull @Positive Long machineId,
        @NotBlank String token,
        @DecimalMin("0") @DecimalMax("100") double cpu,
        @Valid @NotNull ResourceUsage mem,
        @Valid ResourceUsage swap,
        @Valid ResourceUsage disk,
        @Valid NetworkUsage net
    ) {
    }

    record ResourceUsage(
        @PositiveOrZero long total,
        @PositiveOrZero long used
    ) {
        NodeMachineService.ResourceUsage toService() {
            return new NodeMachineService.ResourceUsage(total, used);
        }
    }

    record NetworkUsage(
        @JsonProperty("in_speed") @PositiveOrZero double inSpeed,
        @JsonProperty("out_speed") @PositiveOrZero double outSpeed
    ) {
        NodeMachineService.NetworkUsage toService() {
            return new NodeMachineService.NetworkUsage(inSpeed, outSpeed);
        }
    }

    record MachineNodesResponse(
        List<MachineNode> nodes,
        @JsonProperty("base_config") PollSettings baseConfig
    ) {
    }

    record MachineNode(long id, String type, String name) {
    }

    record PollSettings(
        @JsonProperty("push_interval") int pushInterval,
        @JsonProperty("pull_interval") int pullInterval
    ) {
    }

    record HandshakeResponse(
        WebSocketSettings websocket,
        PollSettings settings
    ) {
    }

    record WebSocketSettings(
        boolean enabled,
        @JsonProperty("ws_url") String wsUrl
    ) {
    }

    record XboardDataResponse(Object data) {
    }
}
