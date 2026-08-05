package com.sinx.platform.node.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.sinx.platform.node.application.NodeProtocolService;

@RestController
@RequestMapping("/api/v2/server")
public class NodeProtocolController {

    private final NodeProtocolService protocol;

    public NodeProtocolController(NodeProtocolService protocol) {
        this.protocol = protocol;
    }

    @GetMapping("/config")
    ResponseEntity<?> config(
        @RequestParam(name = "machine_id") long machineId,
        @RequestParam(name = "node_id") long nodeId,
        @RequestParam String token,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        NodeProtocolService.ConfigPayload payload = protocol.config(machineId, nodeId, token);
        if (payload.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(payload.etag()).build();
        }
        return ResponseEntity.ok().eTag(payload.etag()).body(payload.data());
    }

    @GetMapping("/user")
    ResponseEntity<?> users(
        @RequestParam(name = "machine_id") long machineId,
        @RequestParam(name = "node_id") long nodeId,
        @RequestParam String token,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        NodeProtocolService.UsersPayload payload = protocol.users(machineId, nodeId, token);
        if (payload.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(payload.etag()).build();
        }
        return ResponseEntity.ok().eTag(payload.etag()).body(Map.of("users", payload.users()));
    }

    @PostMapping("/report")
    Map<String, Object> report(@RequestBody Map<String, Object> request) {
        long machineId = ((Number) request.get("machine_id")).longValue();
        long nodeId = ((Number) request.get("node_id")).longValue();
        String token = String.valueOf(request.get("token"));
        Map<String, Object> payload = new LinkedHashMap<>(request);
        payload.remove("machine_id");
        payload.remove("node_id");
        payload.remove("token");
        protocol.report(machineId, nodeId, token, payload);
        return Map.of("data", true);
    }
}
