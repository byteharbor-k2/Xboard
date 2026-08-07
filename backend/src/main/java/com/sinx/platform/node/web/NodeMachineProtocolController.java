package com.sinx.platform.node.web;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.node.application.NodeMachineService;
import com.sinx.platform.node.application.NodeManagementService;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.node.websocket.NodeWebSocketEndpointInfo;

import jakarta.servlet.http.HttpServletRequest;
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
    private final NodeManagementService nodes;
    private final NodeProtocolService protocol;
    private final NodeWebSocketEndpointInfo webSocket;
    private final PlatformConfigurationService configuration;

    public NodeMachineProtocolController(
        NodeMachineService machines,
        NodeManagementService nodes,
        NodeProtocolService protocol,
        NodeWebSocketEndpointInfo webSocket,
        PlatformConfigurationService configuration
    ) {
        this.machines = machines;
        this.nodes = nodes;
        this.protocol = protocol;
        this.webSocket = webSocket;
        this.configuration = configuration;
    }

    @PostMapping("/handshake")
    HandshakeResponse handshake(
        @Valid @RequestBody HandshakeCredentials request,
        HttpServletRequest servletRequest
    ) {
        if (request.machineId() == null) {
            if (request.nodeId() == null) {
                protocol.authenticateLegacyToken(request.token());
            } else {
                protocol.authenticateLegacy(request.nodeId(), request.token());
            }
        } else if (request.nodeId() != null) {
            protocol.authenticate(
                request.machineId(),
                request.nodeId(),
                request.token()
            );
        } else {
            machines.authenticate(request.machineId(), request.token());
        }
        NodeWebSocketEndpointInfo.Endpoint endpoint = webSocket.endpoint(servletRequest);
        return new HandshakeResponse(
            new WebSocketSettings(endpoint.enabled(), endpoint.url()),
            new PollSettings(endpoint.pushIntervalSeconds(), endpoint.pullIntervalSeconds())
        );
    }

    @PostMapping("/machine/nodes")
    MachineNodesResponse nodes(@Valid @RequestBody MachineCredentials request) {
        machines.authenticate(request.machineId(), request.token());
        PlatformConfigurationService.NodeCommunicationSettings communication =
            configuration.nodeCommunicationSettings();
        return new MachineNodesResponse(
            nodes.forMachine(request.machineId(), true).stream()
                .map(node -> new MachineNode(node.id(), node.type(), node.name()))
                .toList(),
            new PollSettings(
                communication.pushIntervalSeconds(),
                communication.pullIntervalSeconds()
            )
        );
    }

    @PostMapping("/machine/status")
    XboardDataResponse status(@Valid @RequestBody MachineStatusRequest request) {
        machines.recordStatus(
            request.machineId(),
            request.token(),
            new NodeMachineService.MachineStatus(
                request.cpu(),
                request.mem().toService(),
                request.swap() == null ? null : request.swap().toService(),
                request.disk() == null ? null : request.disk().toService(),
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

    record HandshakeCredentials(
        @JsonProperty("machine_id") @Positive Long machineId,
        @JsonProperty("node_id") @Positive Long nodeId,
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
