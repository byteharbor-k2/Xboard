package com.sinx.platform.node.application;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.node.domain.NodeAccessGroup;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeAccessGroupRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class NodeAccessGroupService {

    private static final Pattern GROUP_NAME = Pattern.compile(
        "^[\\p{IsHan}A-Za-z0-9_-]{2,50}$"
    );

    private final NodeAccessGroupRepository groups;
    private final ProxyNodeRepository nodes;
    private final ServicePlanRepository plans;
    private final UserAccountRepository users;
    private final SubscriptionEntitlementRepository entitlements;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeAccessGroupService(
        NodeAccessGroupRepository groups,
        ProxyNodeRepository nodes,
        ServicePlanRepository plans,
        UserAccountRepository users,
        SubscriptionEntitlementRepository entitlements,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.groups = groups;
        this.nodes = nodes;
        this.plans = plans;
        this.users = users;
        this.entitlements = entitlements;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public List<GroupView> list() {
        List<ProxyNode> allNodes = nodes.findAll();
        return groups.findAllByOrderByIdDesc().stream()
            .map(group -> view(group, allNodes))
            .toList();
    }

    @Transactional
    public boolean save(Long id, String name) {
        String normalizedName = normalizeName(name);
        if (id == null) {
            if (groups.existsByNameIgnoreCase(normalizedName)) {
                throw duplicateName();
            }
            groups.save(NodeAccessGroup.create(normalizedName, clock.instant()));
            return true;
        }

        NodeAccessGroup group = requireGroup(id);
        if (groups.existsByNameIgnoreCaseAndIdNot(normalizedName, id)) {
            throw duplicateName();
        }
        group.rename(normalizedName, clock.instant());
        return true;
    }

    @Transactional
    public boolean delete(long id) {
        NodeAccessGroup group = requireGroup(id);
        if (nodes.findAll().stream().anyMatch(node -> containsId(node.getGroupIds(), id))) {
            throw inUse("The group is assigned to one or more nodes");
        }
        if (plans.countByServerGroupId(id) > 0) {
            throw inUse("The group is assigned to one or more plans");
        }
        if (users.countByServerGroupId(id) > 0) {
            throw inUse("The group is assigned to one or more users");
        }
        if (activeSubscribers(id) > 0) {
            throw inUse("The group still serves one or more active subscriptions");
        }
        groups.delete(group);
        return true;
    }

    public NodeAccessGroup requireGroup(long id) {
        return groups.findById(id).orElseThrow(() -> new ApiProblemException(
            HttpStatus.NOT_FOUND,
            "NODE_GROUP_NOT_FOUND",
            "Node access group does not exist"
        ));
    }

    private GroupView view(NodeAccessGroup group, List<ProxyNode> allNodes) {
        long serverCount = allNodes.stream()
            .filter(node -> containsId(node.getGroupIds(), group.getId()))
            .count();
        return new GroupView(
            group.getId(),
            group.getName(),
            activeSubscribers(group.getId()),
            serverCount,
            group.getCreatedAt().getEpochSecond(),
            group.getUpdatedAt().getEpochSecond()
        );
    }

    /**
     * Users reach a group through their entitlement, so users.serverGroupId only
     * covers explicit overrides and reports zero for plan-assigned subscribers.
     */
    private long activeSubscribers(long groupId) {
        return entitlements.countActiveForServerGroup(
            groupId,
            clock.instant(),
            UserStatus.ACTIVE
        );
    }

    private boolean containsId(String encodedIds, long id) {
        try {
            Object raw = objectMapper.readValue(encodedIds, Object.class);
            if (!(raw instanceof List<?> values)) return false;
            return values.stream().anyMatch(value -> {
                if (value instanceof Number number) return number.longValue() == id;
                try { return Long.parseLong(value.toString()) == id; }
                catch (RuntimeException ignored) { return false; }
            });
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeName(String name) {
        if (name == null) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_NODE_GROUP",
                "Group name must contain 2 to 50 Chinese or English letters, numbers, underscores, or hyphens"
            );
        }
        String normalized = name.trim();
        if (!GROUP_NAME.matcher(normalized).matches()) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "INVALID_NODE_GROUP",
                "Group name must contain 2 to 50 Chinese or English letters, numbers, underscores, or hyphens"
            );
        }
        return normalized;
    }

    private ApiProblemException duplicateName() {
        return new ApiProblemException(
            HttpStatus.CONFLICT,
            "NODE_GROUP_NAME_EXISTS",
            "A node access group with this name already exists"
        );
    }

    private ApiProblemException inUse(String detail) {
        return new ApiProblemException(HttpStatus.CONFLICT, "NODE_GROUP_IN_USE", detail);
    }

    public record GroupView(
        long id,
        String name,
        @JsonProperty("users_count") long usersCount,
        @JsonProperty("server_count") long serverCount,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("updated_at") long updatedAt
    ) {
    }
}
