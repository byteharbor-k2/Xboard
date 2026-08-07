package com.sinx.platform.node.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.mockito.ArgumentCaptor;

import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
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
    void honorsConfiguredListenAddressAndPollingIntervals() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.node().getProtocolSettings()).thenReturn("{\"listen_ip\":\"127.0.0.2\"}");

        NodeProtocolService.ConfigPayload payload = fixture.service().config(1, 9, "token");

        assertThat(payload.data())
            .containsEntry("listen_ip", "127.0.0.2")
            .containsEntry("base_config", Map.of(
                "push_interval", 19,
                "pull_interval", 17
            ));
    }

    @Test
    void legacyNodeAuthenticationUsesTheGlobalTokenWithoutAMachineAssignment() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.nodes().findFirstByCode("9")).thenReturn(Optional.empty());
        when(fixture.node().getMachine()).thenReturn(null);

        NodeProtocolService.ConfigPayload payload =
            fixture.service().configLegacy(9, "legacy-token");

        assertThat(payload.data()).containsEntry("node_id", 9L);
        verifyNoInteractions(fixture.machines());
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
        verify(fixture.deviceStates()).replaceSnapshot(
            9L, Map.of(101L, List.of("198.51.100.1")), NOW
        );
        verify(fixture.node()).recordReport(5L, 7L, 1, 2, null, null, NOW);
        verifyNoInteractions(fixture.events());
    }

    @Test
    void publishesOneGroupChangeWhenTrafficExhaustsAnEntitlement() {
        Fixture fixture = fixture("[10]", "[]");
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(101L);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(10L);
        when(entitlement.stateAt(NOW)).thenReturn(
            EntitlementState.ACTIVE,
            EntitlementState.EXHAUSTED
        );
        when(fixture.entitlements().findForTrafficReport(101L))
            .thenReturn(Optional.of(entitlement));

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of("101", List.of(5L, 7L))
        ));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(fixture.events()).publishEvent((Object) events.capture());
        assertThat(events.getValue())
            .isInstanceOfSatisfying(
                NodeAccessGroupsChangedEvent.class,
                changed -> {
                    assertThat(changed.groupIds()).isEqualTo(Set.of(10L));
                    assertThat(changed.reason())
                        .isEqualTo("subscription traffic exhausted");
                }
            );
    }

    @Test
    void deduplicatesExhaustedGroupsWithinOneTrafficReport() {
        Fixture fixture = fixture("[10]", "[]");
        SubscriptionEntitlement first = exhaustingEntitlement(101L, 10L);
        SubscriptionEntitlement second = exhaustingEntitlement(102L, 10L);
        when(fixture.entitlements().findForTrafficReport(101L))
            .thenReturn(Optional.of(first));
        when(fixture.entitlements().findForTrafficReport(102L))
            .thenReturn(Optional.of(second));

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of(
                "101", List.of(5L, 7L),
                "102", List.of(5L, 7L)
            )
        ));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(fixture.events()).publishEvent((Object) events.capture());
        assertThat(events.getValue())
            .isInstanceOfSatisfying(
                NodeAccessGroupsChangedEvent.class,
                changed -> assertThat(changed.groupIds())
                    .isEqualTo(Set.of(10L))
            );
    }

    @Test
    void appliesTheCurrentNodeRateOnlyToUserBilling() {
        Fixture fixture = fixture("[10]", "[]");
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(101L);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(10L);
        when(entitlement.stateAt(NOW)).thenReturn(EntitlementState.ACTIVE);
        BigDecimal rate = new BigDecimal("2.5");
        when(fixture.trafficRates().currentRate(fixture.node(), NOW)).thenReturn(rate);
        when(fixture.trafficRates().charge(5L, rate)).thenReturn(12L);
        when(fixture.trafficRates().charge(7L, rate)).thenReturn(17L);
        when(fixture.entitlements().findForTrafficReport(101L))
            .thenReturn(Optional.of(entitlement));

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of("101", List.of(5L, 7L))
        ));

        verify(entitlement).addUsage(12L, 17L, NOW);
        verify(fixture.node()).recordReport(5L, 7L, 1, 0, null, null, NOW);
    }

    @Test
    void retainsOnlineCountersWhenAnUnchangedSnapshotIsOmitted() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.node().getOnlineUsers()).thenReturn(4);
        when(fixture.node().getOnlineConnections()).thenReturn(9);

        fixture.service().report(1, 9, "token", Map.of("cpu", 1));

        verify(fixture.node()).recordReport(0L, 0L, 4, 9, null, null, NOW);
    }

    @Test
    void partialReportsPreserveTheLastStatusAndMetrics() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.node().getOnlineUsers()).thenReturn(4);
        when(fixture.node().getOnlineConnections()).thenReturn(9);
        when(fixture.node().getLoadStatus()).thenReturn("{\"cpu\":12}");
        when(fixture.node().getMetrics()).thenReturn("{\"uptime\":60}");

        fixture.service().report(1, 9, "token", Map.of(
            "alive", Map.of("101", List.of("198.51.100.1"))
        ));

        verify(fixture.node()).recordReport(
            0L,
            0L,
            4,
            9,
            "{\"cpu\":12}",
            "{\"uptime\":60}",
            NOW
        );
    }

    @Test
    void legacyAliveListOnlyIncludesDeviceLimitedUsersAndReturnsCounts() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.nodes().findFirstByCode("9")).thenReturn(Optional.empty());
        SubscriptionEntitlement limited = entitlement(101L, 3);
        SubscriptionEntitlement unlimited = entitlement(102L, 0);
        when(fixture.entitlements().findAllWithUserAndPlan())
            .thenReturn(List.of(limited, unlimited));
        when(fixture.deviceStates().snapshotForUsers(Set.of(101L), NOW))
            .thenReturn(Map.of(101L, List.of("198.51.100.1", "2001:db8::1")));

        Map<Long, Integer> result = fixture.service()
            .aliveListLegacy(9L, "legacy-token");

        assertThat(result).containsExactly(Map.entry(101L, 2));
        verify(fixture.deviceStates()).snapshotForUsers(Set.of(101L), NOW);
    }

    @Test
    void stillCountsNodeTrafficWhenTheReportedUserIsUnknown() {
        Fixture fixture = fixture("[10]", "[]");
        when(fixture.entitlements().findForTrafficReport(404L))
            .thenReturn(Optional.empty());

        fixture.service().report(1, 9, "token", Map.of(
            "traffic", Map.of("404", List.of(11L, 13L))
        ));

        verify(fixture.node()).recordReport(11L, 13L, 1, 0, null, null, NOW);
    }

    private Fixture fixture(String groupIds, String routeIds) {
        NodeMachineService machines = mock(NodeMachineService.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        NodeRouteRuleRepository routes = mock(NodeRouteRuleRepository.class);
        SubscriptionEntitlementRepository entitlements =
            mock(SubscriptionEntitlementRepository.class);
        NodeTrafficRateCalculator trafficRates = mock(NodeTrafficRateCalculator.class);
        NodeDeviceStateService deviceStates = mock(NodeDeviceStateService.class);
        PlatformConfigurationService configuration = mock(PlatformConfigurationService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
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
        when(trafficRates.currentRate(node, NOW)).thenReturn(BigDecimal.ONE);
        when(trafficRates.charge(5L, BigDecimal.ONE)).thenReturn(5L);
        when(trafficRates.charge(7L, BigDecimal.ONE)).thenReturn(7L);
        when(configuration.nodeCommunicationSettings()).thenReturn(
            new PlatformConfigurationService.NodeCommunicationSettings(
                "legacy-token", 17, 19, true, null
            )
        );

        NodeProtocolService service = new NodeProtocolService(
            machines, nodes, routes, entitlements, trafficRates, deviceStates,
            configuration, events, new ObjectMapper(), CLOCK
        );
        return new Fixture(
            service, machines, node, nodes, routes, entitlements, trafficRates,
            deviceStates, events
        );
    }

    private SubscriptionEntitlement exhaustingEntitlement(
        long nodeUserId,
        long groupId
    ) {
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(nodeUserId);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(groupId);
        when(entitlement.stateAt(NOW)).thenReturn(
            EntitlementState.ACTIVE,
            EntitlementState.EXHAUSTED
        );
        return entitlement;
    }

    private SubscriptionEntitlement entitlement(long nodeUserId, int deviceLimit) {
        UserAccount user = mock(UserAccount.class);
        SubscriptionEntitlement entitlement = mock(SubscriptionEntitlement.class);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getNodeUserId()).thenReturn(nodeUserId);
        when(user.getId()).thenReturn(new UUID(0, nodeUserId));
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getEffectiveServerGroupId()).thenReturn(10L);
        when(entitlement.stateAt(NOW)).thenReturn(EntitlementState.ACTIVE);
        when(entitlement.getDeviceLimit()).thenReturn(deviceLimit);
        return entitlement;
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
        NodeMachineService machines,
        ProxyNode node,
        ProxyNodeRepository nodes,
        NodeRouteRuleRepository routes,
        SubscriptionEntitlementRepository entitlements,
        NodeTrafficRateCalculator trafficRates,
        NodeDeviceStateService deviceStates,
        ApplicationEventPublisher events
    ) {
    }
}
