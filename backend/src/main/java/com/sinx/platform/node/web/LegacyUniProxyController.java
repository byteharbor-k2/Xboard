package com.sinx.platform.node.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.application.NodeProtocolService;
import com.sinx.platform.shared.web.ApiProblemException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Compatibility surface used by xboard-node when it runs in legacy
 * single-node mode. Machine mode continues to use the v2 endpoints.
 */
@RestController
@RequestMapping("/api/v1/server/UniProxy")
public class LegacyUniProxyController {

    private final NodeProtocolService protocol;

    public LegacyUniProxyController(NodeProtocolService protocol) {
        this.protocol = protocol;
    }

    @GetMapping("/config")
    ResponseEntity<?> config(
        @RequestParam(name = "node_id") long nodeId,
        @RequestParam String token,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        NodeProtocolService.ConfigPayload payload = protocol.configLegacy(nodeId, token);
        if (payload.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(payload.etag()).build();
        }
        return ResponseEntity.ok().eTag(payload.etag()).body(payload.data());
    }

    @GetMapping("/user")
    ResponseEntity<?> users(
        @RequestParam(name = "node_id") long nodeId,
        @RequestParam String token,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        NodeProtocolService.UsersPayload payload = protocol.usersLegacy(nodeId, token);
        if (payload.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(payload.etag()).build();
        }
        return ResponseEntity.ok().eTag(payload.etag()).body(Map.of("users", payload.users()));
    }

    @PostMapping("/push")
    Map<String, Object> push(@RequestBody Map<String, Object> request) {
        LegacyRequest payload = legacyRequest(request);
        protocol.reportLegacy(
            payload.nodeId(),
            payload.token(),
            Map.of("traffic", payload.data())
        );
        return Map.of("data", true);
    }

    @PostMapping("/alive")
    Map<String, Object> alive(@RequestBody Map<String, Object> request) {
        LegacyRequest payload = legacyRequest(request);
        protocol.reportLegacy(
            payload.nodeId(),
            payload.token(),
            Map.of("alive", payload.data())
        );
        return Map.of("data", true);
    }

    @GetMapping("/alivelist")
    Map<String, Object> aliveList(
        @RequestParam(name = "node_id") long nodeId,
        @RequestParam String token
    ) {
        return Map.of("alive", protocol.aliveListLegacy(nodeId, token));
    }

    @PostMapping("/status")
    Map<String, Object> status(@Valid @RequestBody LegacyStatusRequest request) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cpu", request.cpu());
        status.put("mem", request.mem().asMap());
        status.put("swap", request.swap().asMap());
        status.put("disk", request.disk().asMap());
        protocol.reportLegacy(
            request.nodeId(),
            request.token(),
            Map.of("status", status)
        );
        return Map.of("data", true, "code", 0, "message", "success");
    }

    private LegacyRequest legacyRequest(Map<String, Object> request) {
        long nodeId = requiredPositiveLong(request.get("node_id"));
        Object tokenValue = request.get("token");
        if (!(tokenValue instanceof String token) || token.isBlank()) {
            throw invalidRequest("token is required");
        }
        Map<String, Object> data = new LinkedHashMap<>(request);
        data.remove("token");
        data.remove("node_id");
        data.remove("node_type");
        return new LegacyRequest(nodeId, token, data);
    }

    private long requiredPositiveLong(Object value) {
        try {
            long parsed = value instanceof Number number
                ? number.longValue()
                : Long.parseLong(String.valueOf(value));
            if (parsed > 0) return parsed;
        } catch (RuntimeException ignored) {
        }
        throw invalidRequest("node_id must be a positive integer");
    }

    private ApiProblemException invalidRequest(String detail) {
        return new ApiProblemException(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "INVALID_LEGACY_NODE_REQUEST",
            detail
        );
    }

    private record LegacyRequest(long nodeId, String token, Map<String, Object> data) {
    }

    record LegacyStatusRequest(
        @JsonProperty("node_id") @NotNull @Positive Long nodeId,
        @NotBlank String token,
        @JsonProperty("node_type") String nodeType,
        @NotNull @DecimalMin("0") @DecimalMax("100") Double cpu,
        @Valid @NotNull ResourceUsage mem,
        @Valid @NotNull ResourceUsage swap,
        @Valid @NotNull ResourceUsage disk
    ) {
    }

    record ResourceUsage(
        @PositiveOrZero long total,
        @PositiveOrZero long used
    ) {
        Map<String, Object> asMap() {
            return Map.of("total", total, "used", used);
        }
    }
}
