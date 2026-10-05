package com.sinx.platform.subscription.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.node.application.NodeTrafficRateCalculator;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * The connection-free node information an account is allowed to see.
 *
 * It follows the subscription selector's audience: a live entitlement, an
 * active/addressable account, and a shown, enabled node serving the account's
 * effective group. Only the node's public name, protocol, tags, calculated
 * traffic rate, and report-derived status cross the user API boundary.
 */
@Service
@Transactional(readOnly = true)
public class ViewerNodeService {

    private final SubscriptionEntitlementRepository entitlements;
    private final ProxyNodeRepository nodes;
    private final NodeTrafficRateCalculator trafficRates;
    private final PlatformConfigurationService configuration;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ViewerNodeService(
        SubscriptionEntitlementRepository entitlements,
        ProxyNodeRepository nodes,
        NodeTrafficRateCalculator trafficRates,
        PlatformConfigurationService configuration,
        ObjectMapper mapper,
        Clock clock
    ) {
        this.entitlements = entitlements;
        this.nodes = nodes;
        this.trafficRates = trafficRates;
        this.configuration = configuration;
        this.mapper = mapper;
        this.clock = clock;
    }

    public List<ViewerNode> viewerNodes(UUID userId) {
        Instant now = clock.instant();
        SubscriptionEntitlement entitlement = entitlements.findByUserId(userId)
            .filter(value -> value.stateAt(now) == EntitlementState.ACTIVE)
            .orElse(null);
        if (entitlement == null) return List.of();

        var account = entitlement.getUser();
        Long groupId = entitlement.getEffectiveServerGroupId();
        if (account.getStatus() != UserStatus.ACTIVE
            || account.getNodeUserId() == null
            || groupId == null) {
            return List.of();
        }

        int pushIntervalSeconds = configuration.nodeCommunicationSettings()
            .pushIntervalSeconds();
        // Reports should arrive once per configured push interval. Allow one
        // missed report before calling a node offline; a never-reported node is
        // kept distinct as UNKNOWN rather than being labelled offline.
        Duration reportWindow = Duration.ofSeconds(
            Math.max(1L, (long) Math.max(1, pushIntervalSeconds) * 2)
        );
        Instant onlineSince = now.minus(reportWindow);
        List<ViewerNode> selected = new ArrayList<>();
        for (ProxyNode node : nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()) {
            if (!serves(node, groupId) || !hasClientAddress(node)) continue;
            Instant lastSeenAt = latest(node.getLastCheckAt(), node.getLastPushAt());
            selected.add(new ViewerNode(
                Long.toString(node.getId()),
                node.getName(),
                node.getType(),
                trafficRates.currentRate(node, now).toPlainString(),
                status(lastSeenAt, onlineSince),
                lastSeenAt == null ? null : lastSeenAt.toString(),
                tags(node.getTags())
            ));
        }
        return List.copyOf(selected);
    }

    private boolean hasClientAddress(ProxyNode node) {
        if (node.getHost() == null || node.getHost().isBlank()) return false;
        Integer port = node.getPort();
        int effectivePort = port == null || port <= 0 ? node.getServerPort() : port;
        return effectivePort > 0 && effectivePort <= 65_535;
    }

    private boolean serves(ProxyNode node, long groupId) {
        try {
            Object decoded = mapper.readValue(node.getGroupIds(), Object.class);
            if (!(decoded instanceof List<?> ids)) return false;
            return ids.stream().anyMatch(value -> value instanceof Number number
                ? number.longValue() == groupId
                : value != null && Long.toString(groupId).equals(String.valueOf(value)));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private List<String> tags(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        try {
            Object decoded = mapper.readValue(encoded, Object.class);
            if (!(decoded instanceof List<?> values)) return List.of();
            return values.stream().map(String::valueOf).toList();
        } catch (RuntimeException exception) {
            return List.of();
        }
    }

    private Instant latest(Instant lastCheckAt, Instant lastPushAt) {
        if (lastCheckAt == null) return lastPushAt;
        if (lastPushAt == null) return lastCheckAt;
        return lastCheckAt.isAfter(lastPushAt) ? lastCheckAt : lastPushAt;
    }

    private ViewerNodeOnlineStatus status(Instant lastSeenAt, Instant onlineSince) {
        if (lastSeenAt == null) return ViewerNodeOnlineStatus.UNKNOWN;
        return lastSeenAt.isBefore(onlineSince)
            ? ViewerNodeOnlineStatus.OFFLINE
            : ViewerNodeOnlineStatus.ONLINE;
    }

    public record ViewerNode(
        String id,
        String name,
        String protocol,
        String trafficRate,
        ViewerNodeOnlineStatus onlineStatus,
        String lastSeenAt,
        List<String> tags
    ) {
    }

    public enum ViewerNodeOnlineStatus {
        ONLINE,
        OFFLINE,
        UNKNOWN
    }
}
