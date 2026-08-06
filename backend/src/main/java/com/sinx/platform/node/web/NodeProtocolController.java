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
import com.sinx.platform.shared.web.ApiProblemException;

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
        long machineId = requiredPositiveLong(request, "machine_id");
        long nodeId = requiredPositiveLong(request, "node_id");
        String token = requiredToken(request);
        Map<String, Object> payload = new LinkedHashMap<>(request);
        payload.remove("machine_id");
        payload.remove("node_id");
        payload.remove("token");
        protocol.report(machineId, nodeId, token, payload);
        return Map.of("data", true);
    }

    private long requiredPositiveLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        long parsed;
        try {
            parsed = value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
        } catch (RuntimeException exception) {
            throw invalidReport(key + " must be a positive integer");
        }
        if (parsed <= 0) throw invalidReport(key + " must be a positive integer");
        return parsed;
    }

    private String requiredToken(Map<String, Object> request) {
        Object value = request.get("token");
        if (!(value instanceof String token) || token.isBlank()) {
            throw invalidReport("token is required");
        }
        return token;
    }

    private ApiProblemException invalidReport(String detail) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_NODE_REPORT",
            detail
        );
    }
}
