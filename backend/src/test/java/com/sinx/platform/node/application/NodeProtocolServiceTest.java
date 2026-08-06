package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.node.domain.NodeMachine;
import com.sinx.platform.node.domain.NodeRouteRule;
import com.sinx.platform.node.domain.ProxyNode;
import com.sinx.platform.node.repository.NodeRouteRuleRepository;
import com.sinx.platform.node.repository.ProxyNodeRepository;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

class NodeProtocolServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sendsAnActiveEntitlementUsingItsEffectivePlanGroup() {
        Fixture fixture = fixture("[10]", "[]");
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(101L);
        when(user.getId()).thenReturn(userId);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(10L);
        when(entitlement.stateAt(NOW)).thenReturn(EntitlementState.ACTIVE);
        when(entitlement.getSpeedLimitMbps()).thenReturn(50);
        when(entitlement.getDeviceLimit()).thenReturn(3);
        when(fixture.entitlements().findAllWithUserAndPlan()).thenReturn(List.of(entitlement));

        NodeProtocolService.UsersPayload payload = fixture.service().users(1, 9, "token");

        assertThat(payload.users()).containsExactly(Map.of(
            "id", 101L,
            "uuid", userId.toString(),
            "speed_limit", 50,
            "device_limit", 3
        ));
    }

    @Test
    void preservesConfiguredRouteOrderAndRemovesDuplicateIds() {
        Fixture fixture = fixture("[10]", "[9,7,9]");
        NodeRouteRule routeSeven = route(7, "direct");
        NodeRouteRule routeNine = route(9, "block");
        when(fixture.routes().findAllById(List.of(9L, 7L, 9L)))
            .thenReturn(List.of(routeSeven, routeNine));

        NodeProtocolService.ConfigPayload payload = fixture.service().config(1, 9, "token");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> routes =
            (List<Map<String, Object>>) payload.data().get("routes");
        assertThat(routes).extracting(route -> route.get("id"))
            .containsExactly(9L, 7L);
    }

    @Test
    void writesOnlyEligibleTrafficDeltasAndNodeStatus() {
        Fixture fixture = fixture("[10]", "[]");
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(101L);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(10L);
        when(entitlement.stateAt(NOW)).thenReturn(EntitlementState.ACTIVE);
        when(fixture.entitlements().findForTrafficReport(101L))
            .thenReturn(Optional.of(entitlement));

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of("101", List.of(5L, 7L)),
            "alive", Map.of("101", List.of("198.51.100.1")),
            "online", Map.of("101", 2)
        ));

        verify(entitlement).addUsage(5L, 7L, NOW);
        verify(fixture.node()).recordReport(5L, 7L, 1, 2, null, null, NOW);
    }

    @Test
    void stillCountsNodeTrafficWhenTheReportedUserIsUnknown() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.entitlements().findForTrafficReport(404L))
            .thenReturn(Optional.empty());

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of("404", List.of(11L, 13L))
        ));

        verify(fixture.node()).recordReport(11L, 13L, 0, 0, null, null, NOW);
    }

    private Fixture fixture(String groupIds, String routeIds) {
        NodeMachineService machines = mock(NodeMachineService.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        NodeRouteRuleRepository routes = mock(NodeRouteRuleRepository.class);
        SubscriptionEntitlementRepository entitlements =
            mock(SubscriptionEntitlementRepository.class);
        NodeMachine machine = mock(NodeMachine.class);
        ProxyNode node = mock(ProxyNode.class);
        when(machine.getId()).thenReturn(1L);
        when(machines.authenticate(1L, "token")).thenReturn(machine);
        when(nodes.findById(9L)).thenReturn(Optional.of(node));
        when(node.getMachine()).thenReturn(machine);
        when(node.isEnabled()).thenReturn(true);
        when(node.getId()).thenReturn(9L);
        when(node.getType()).thenReturn("shadowsocks");
        when(node.getProtocolSettings()).thenReturn("{}");
        when(node.getGroupIds()).thenReturn(groupIds);
        when(node.getRouteIds()).thenReturn(routeIds);
        when(node.getCustomOutbounds()).thenReturn("[]");
        when(node.getCustomRoutes()).thenReturn("[]");

        NodeProtocolService service = new NodeProtocolService(
            machines, nodes, routes, entitlements, new ObjectMapper(), CLOCK
        );
        return new Fixture(service, node, routes, entitlements);
    }

    private NodeRouteRule route(long id, String action) {
        NodeRouteRule route = mock(NodeRouteRule.class);
        when(route.getId()).thenReturn(id);
        when(route.getMatchRules()).thenReturn("[\"domain:example.test\"]");
        when(route.getAction()).thenReturn(action);
        return route;
    }

    private record Fixture(
        NodeProtocolService service,
        ProxyNode node,
        NodeRouteRuleRepository routes,
        SubscriptionEntitlementRepository entitlements
    ) {
    }
}
