package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.node.domain.NodeAccessGroup;
import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.NodeRouteRule;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeAccessGroupRepository;
import com.sinx.platform.node.repository.NodeMachineLoadHistoryRepository;
import com.sinx.platform.node.repository.NodeMachineRepository;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

class NodeAdministrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void refusesToDeleteAGroupReferencedByANode() {
        NodeAccessGroupRepository groups = mock(NodeAccessGroupRepository.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        ServicePlanRepository plans = mock(ServicePlanRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        NodeAccessGroup group = NodeAccessGroup.create("Premium", NOW);
        ProxyNode node = nodeWithAssociations("[12]", "[]");
        when(groups.findById(12L)).thenReturn(Optional.of(group));
        when(nodes.findAll()).thenReturn(List.of(node));

        NodeAccessGroupService service = new NodeAccessGroupService(
            groups, nodes, plans, users,
            mock(SubscriptionEntitlementRepository.class), new ObjectMapper(), CLOCK
        );

        assertThatThrownBy(() -> service.delete(12L))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("NODE_GROUP_IN_USE");
    }

    @Test
    void refusesToDeleteAGroupReferencedByAPlanOrUser() {
        NodeAccessGroupRepository groups = mock(NodeAccessGroupRepository.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        ServicePlanRepository plans = mock(ServicePlanRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        NodeAccessGroup group = NodeAccessGroup.create("Subscribers", NOW);
        when(groups.findById(21L)).thenReturn(Optional.of(group));
        when(nodes.findAll()).thenReturn(List.of());
        when(plans.countByServerGroupId(21L)).thenReturn(1L);

        NodeAccessGroupService service = new NodeAccessGroupService(
            groups, nodes, plans, users,
            mock(SubscriptionEntitlementRepository.class), new ObjectMapper(), CLOCK
        );
        assertThatThrownBy(() -> service.delete(21L))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("NODE_GROUP_IN_USE");

        when(plans.countByServerGroupId(21L)).thenReturn(0L);
        when(users.countByServerGroupId(21L)).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(21L))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("NODE_GROUP_IN_USE");
    }

    @Test
    void refusesToDeleteAGroupThatStillServesActiveSubscriptions() {
        NodeAccessGroupRepository groups = mock(NodeAccessGroupRepository.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        ServicePlanRepository plans = mock(ServicePlanRepository.class);
        UserAccountRepository users = mock(UserAccountRepository.class);
        SubscriptionEntitlementRepository entitlements =
            mock(SubscriptionEntitlementRepository.class);
        NodeAccessGroup group = NodeAccessGroup.create("Subscribers", NOW);
        when(groups.findById(33L)).thenReturn(Optional.of(group));
        when(nodes.findAll()).thenReturn(List.of());
        // Nothing references the group directly; the subscribers reached it
        // through their plan, which users.serverGroupId does not record.
        when(plans.countByServerGroupId(33L)).thenReturn(0L);
        when(users.countByServerGroupId(33L)).thenReturn(0L);
        when(entitlements.countActiveForServerGroup(33L, NOW, UserStatus.ACTIVE))
            .thenReturn(4L);

        NodeAccessGroupService service = new NodeAccessGroupService(
            groups, nodes, plans, users, entitlements, new ObjectMapper(), CLOCK
        );

        assertThatThrownBy(() -> service.delete(33L))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("NODE_GROUP_IN_USE");
    }

    @Test
    void machineAuthenticationLeavesTheSharedMachineRowUntouched() {
        NodeMachineRepository machines = mock(NodeMachineRepository.class);
        NodeMachine machine = NodeMachine.create("SG", "token", null, true, NOW);
        when(machines.findByIdAndToken(7L, "token"))
            .thenReturn(Optional.of(machine));
        NodeMachineService service = new NodeMachineService(
            machines,
            mock(NodeMachineLoadHistoryRepository.class),
            mock(ProxyNodeRepository.class),
            new ObjectMapper(),
            CLOCK
        );

        service.authenticate(7L, "token");

        // Every node request authenticates. Writing a heartbeat here made two
        // nodes on one machine collide on its optimistic-lock version, and the
        // loser's transaction rolled back a traffic report that the node had
        // already counted.
        assertThat(machine.getLastSeenAt()).isNull();
    }

    @Test
    void validatesGroupNamesUsingTheOriginalAdminUiRules() {
        NodeAccessGroupRepository groups = mock(NodeAccessGroupRepository.class);
        NodeAccessGroupService service = new NodeAccessGroupService(
            groups,
            mock(ProxyNodeRepository.class),
            mock(ServicePlanRepository.class),
            mock(UserAccountRepository.class),
            mock(SubscriptionEntitlementRepository.class),
            new ObjectMapper(),
            CLOCK
        );

        assertThat(service.save(null, " 用户_Group-01 ")).isTrue();
        verify(groups).save(org.mockito.ArgumentMatchers.argThat(group ->
            group.getName().equals("用户_Group-01")
        ));

        for (String invalidName : List.of("A", "bad group", "group!", "😀😀")) {
            assertThatThrownBy(() -> service.save(null, invalidName))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).getCode())
                .isEqualTo("INVALID_NODE_GROUP");
        }
    }

    @Test
    void deletingAMachineExplicitlyUnbindsItsNodesBeforeDeletingIt() {
        NodeMachineRepository machines = mock(NodeMachineRepository.class);
        NodeMachineLoadHistoryRepository history = mock(NodeMachineLoadHistoryRepository.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        NodeMachine machine = mock(NodeMachine.class);
        ProxyNode first = mock(ProxyNode.class);
        ProxyNode second = mock(ProxyNode.class);
        when(machines.findById(5L)).thenReturn(Optional.of(machine));
        when(nodes.findByMachineIdOrderBySortOrderAscIdAsc(5L))
            .thenReturn(List.of(first, second));

        NodeMachineService service = new NodeMachineService(
            machines, history, nodes, new ObjectMapper(), CLOCK
        );
        service.delete(5L);

        verify(first).quickUpdate(null, null, null, true, NOW);
        verify(second).quickUpdate(null, null, null, true, NOW);
        InOrder order = inOrder(nodes, machines);
        order.verify(nodes).saveAll(List.of(first, second));
        order.verify(nodes).flush();
        order.verify(machines).delete(machine);
    }

    @Test
    void deletingARouteRemovesItFromEveryNode() {
        NodeRouteRuleRepository routes = mock(NodeRouteRuleRepository.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        NodeRouteRule route = NodeRouteRule.create(
            "Block telemetry", "[\"domain:telemetry.example\"]", "block", null, NOW
        );
        ProxyNode node = nodeWithAssociations("[]", "[7,8]");
        when(routes.findById(7L)).thenReturn(Optional.of(route));
        when(nodes.findAll()).thenReturn(List.of(node));

        NodeRouteRuleService service = new NodeRouteRuleService(
            routes, nodes, new ObjectMapper(), CLOCK
        );
        assertThat(service.delete(7L)).isTrue();

        assertThat(node.getRouteIds()).isEqualTo("[8]");
        assertThat(node.getUpdatedAt()).isEqualTo(NOW);
        verify(routes).delete(route);
    }

    @Test
    void normalizesRouteMatchesAndSupportsEveryXboardAction() {
        for (String action : List.of("block", "direct", "dns", "proxy")) {
            NodeRouteRuleRepository routes = mock(NodeRouteRuleRepository.class);
            NodeRouteRuleService service = new NodeRouteRuleService(
                routes, mock(ProxyNodeRepository.class), new ObjectMapper(), CLOCK
            );
            assertThat(service.save(
                null,
                "  Route " + action + "  ",
                List.of(" domain:example.com ", "", "domain:example.com"),
                action.toUpperCase(),
                "  outbound "
            )).isTrue();
            verify(routes).save(org.mockito.ArgumentMatchers.argThat(route ->
                route.getRemarks().equals("Route " + action)
                    && route.getMatchRules().equals("[\"domain:example.com\"]")
                    && route.getAction().equals(action)
                    && (action.equals("proxy")
                        ? route.getActionValue().equals("outbound")
                        : route.getActionValue() == null)
            ));
        }
    }

    @Test
    void requiresAnActionValueForProxyRoutes() {
        NodeRouteRuleService service = new NodeRouteRuleService(
            mock(NodeRouteRuleRepository.class),
            mock(ProxyNodeRepository.class),
            new ObjectMapper(),
            CLOCK
        );

        assertThatThrownBy(() -> service.save(
            null,
            "Proxy selected traffic",
            List.of("domain:example.com"),
            "proxy",
            "  "
        ))
            .isInstanceOf(ApiProblemException.class)
            .extracting(exception -> ((ApiProblemException) exception).getCode())
            .isEqualTo("INVALID_NODE_ROUTE");
    }

    private ProxyNode nodeWithAssociations(String groupIds, String routeIds) {
        ProxyNode node = ProxyNode.create(NOW.minusSeconds(30));
        node.configure(
            "shadowsocks", null, null, null,
            groupIds, routeIds, "Test node", BigDecimal.ONE,
            false, "[]", 0, "[]", "node.example.test", 443, 443,
            "{}", "[]", "[]", null, true, true, 0, NOW.minusSeconds(20)
        );
        return node;
    }
}
