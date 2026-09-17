package com.sinx.platform.subscription.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.subscription.client.NodeClientView;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

import tools.jackson.databind.ObjectMapper;

/**
 * The nodes one account is entitled to see.
 *
 * The predicate is the same one the node control plane applies when it decides
 * who to push to a node ({@code NodeProtocolService.users}) — active account,
 * an account the nodes know about, and a group the node serves — run the other
 * way round. That symmetry is the point: a node named in a subscription but
 * absent from the node's user list is a node the client will fail to connect
 * to, and a node in the user list but absent from the subscription is capacity
 * nobody can use.
 *
 * Two deliberate differences from the original panel:
 *
 * <ul>
 *   <li>The node's own traffic counter is not consulted. This panel's
 *       {@code ProxyNode.transferEnable} is not read anywhere else either; node
 *       quotas are enforced by the control plane reacting to a node's own
 *       report. A second gate here would make a node vanish from subscriptions
 *       while it is still being pushed users, which is the one state that
 *       cannot be explained to a customer.</li>
 *   <li>The account's node identity is the account id itself, matching
 *       {@code NodeProtocolService.userPayload}. It is not the uuid column the
 *       node database carries for other purposes.</li>
 * </ul>
 */
@Component
public class SubscriptionNodeSelector {

    private final ProxyNodeRepository nodes;
    private final ObjectMapper mapper;

    public SubscriptionNodeSelector(
        ProxyNodeRepository nodes,
        ObjectMapper mapper
    ) {
        this.nodes = nodes;
        this.mapper = mapper;
    }

    /**
     * Every node this entitlement may use, in the panel's own order.
     *
     * An account that would not be pushed to any node gets an empty list, not
     * an error: the endpoint above turns that into whatever an empty
     * subscription should look like for the format being served.
     */
    public List<NodeClientView> select(SubscriptionEntitlement entitlement) {
        UserAccount user = entitlement.getUser();
        Long groupId = entitlement.getEffectiveServerGroupId();
        if (
            user.getStatus() != UserStatus.ACTIVE
                || user.getNodeUserId() == null
                || groupId == null
        ) {
            return List.of();
        }

        String identity = user.getId().toString();
        List<NodeClientView> selected = new ArrayList<>();
        for (ProxyNode node : nodes.findByEnabledTrueAndShowTrueOrderBySortOrderAscIdAsc()) {
            if (!serves(node, groupId)) {
                continue;
            }
            NodeClientView view = NodeClientView.of(node, mapper, identity);
            if (view != null) {
                selected.add(view);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Whether a node's group list names this group.
     *
     * The column is JSON text, so this cannot be a query predicate; the ids are
     * written as numbers by the panel and as strings by nothing else, but both
     * are accepted because an operator editing the column by hand should not be
     * able to silently empty a node's audience.
     */
    private boolean serves(ProxyNode node, long groupId) {
        try {
            if (!(mapper.readValue(node.getGroupIds(), Object.class) instanceof List<?> ids)) {
                return false;
            }
            for (Object value : ids) {
                if (value instanceof Number number && number.longValue() == groupId) {
                    return true;
                }
                if (value != null && String.valueOf(value).equals(Long.toString(groupId))) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
