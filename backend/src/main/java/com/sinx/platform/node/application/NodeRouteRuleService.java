package com.sinx.platform.node.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.node.domain.NodeRouteRule;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NodeRouteRuleService {

    private static final Set<String> ACTIONS = Set.of("block", "direct", "dns", "proxy");

    private final NodeRouteRuleRepository routes;
    private final ProxyNodeRepository nodes;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeRouteRuleService(
        NodeRouteRuleRepository routes,
        ProxyNodeRepository nodes,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.routes = routes;
        this.nodes = nodes;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<RouteView> list() {
        return routes.findAllByOrderByIdAsc().stream().map(this::view).toList();
    }

    @Transactional
    public boolean save(Long id, String remarks, List<String> matches, String action, String actionValue) {
        String normalizedRemarks = normalizeRemarks(remarks);
        List<String> normalizedMatches = normalizeMatches(matches);
        String normalizedAction = normalizeAction(action);
        String normalizedValue = normalizeActionValue(normalizedAction, actionValue);
        Instant now = clock.instant();

        if (id == null) {
            routes.save(NodeRouteRule.create(
                normalizedRemarks,
                json(normalizedMatches),
                normalizedAction,
                normalizedValue,
                now
            ));
            return true;
        }

        requireRoute(id).configure(
            normalizedRemarks,
            json(normalizedMatches),
            normalizedAction,
            normalizedValue,
            now
        );
        return true;
    }

    @Transactional
    public boolean delete(long id) {
        NodeRouteRule route = requireRoute(id);
        Instant now = clock.instant();
        for (ProxyNode node : nodes.findAll()) {
            List<Long> routeIds = decodeIds(node.getRouteIds());
            if (!routeIds.contains(id)) continue;
            node.replaceRouteIds(
                json(routeIds.stream().filter(routeId -> routeId != id).toList()),
                now
            );
        }
        routes.delete(route);
        return true;
    }

    private NodeRouteRule requireRoute(long id) {
        return routes.findById(id).orElseThrow(() -> new ApiProblemException(
            HttpStatus.NOT_FOUND,
            "NODE_ROUTE_NOT_FOUND",
            "Node route rule does not exist"
        ));
    }

    private RouteView view(NodeRouteRule route) {
        return new RouteView(
            route.getId(),
            route.getRemarks(),
            decodeStrings(route.getMatchRules()),
            route.getAction(),
            route.getActionValue(),
            route.getCreatedAt().getEpochSecond(),
            route.getUpdatedAt().getEpochSecond()
        );
    }

    private String normalizeRemarks(String remarks) {
        if (remarks == null || remarks.isBlank()) throw invalid("Route remarks are required");
        String normalized = remarks.trim();
        if (normalized.length() > 255) throw invalid("Route remarks must not exceed 255 characters");
        return normalized;
    }

    private List<String> normalizeMatches(List<String> matches) {
        if (matches == null) throw invalid("At least one match rule is required");
        List<String> normalized = matches.stream()
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                ArrayList::new
            ));
        if (normalized.isEmpty()) throw invalid("At least one match rule is required");
        return List.copyOf(normalized);
    }

    private String normalizeAction(String action) {
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(normalized)) throw invalid("Unsupported route action");
        return normalized;
    }

    private String normalizeActionValue(String action, String actionValue) {
        if (!"proxy".equals(action)) return null;
        String normalized = blankToNull(actionValue);
        if (normalized == null) throw invalid("Proxy route action value is required");
        return normalized;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw invalid("Route rule contains invalid data"); }
    }

    private List<Long> decodeIds(String encoded) {
        try {
            Object raw = objectMapper.readValue(encoded, Object.class);
            if (!(raw instanceof List<?> values)) return List.of();
            return values.stream().map(value -> {
                if (value instanceof Number number) return number.longValue();
                return Long.parseLong(value.toString());
            }).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> decodeStrings(String encoded) {
        try {
            Object raw = objectMapper.readValue(encoded, Object.class);
            if (!(raw instanceof List<?> values)) return List.of();
            return values.stream().map(Object::toString).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_NODE_ROUTE", detail);
    }

    public record RouteView(
        long id,
        String remarks,
        @JsonProperty("match") List<String> matches,
        String action,
        @JsonProperty("action_value") String actionValue,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) {
    }
}
