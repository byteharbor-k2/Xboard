package com.sinx.platform.catalog.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.node.application.NodeAccessGroupsChangedEvent;
import com.sinx.platform.node.repository.NodeAccessGroupRepository;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

class PlanManagementServiceTest {

    private static final UUID PLAN_ID = UUID.fromString(
        "00000000-0000-0000-0000-000000000201"
    );
    private static final Instant NOW = Instant.parse("2026-08-07T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void publishesOldAndNewGroupsWhenAPlanMovesBetweenGroups() {
        Fixture fixture = fixture(10L);
        when(fixture.groups().existsById(20L)).thenReturn(true);

        fixture.service().update(PLAN_ID, draft(20L, "Updated plan"));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(fixture.events()).publishEvent((Object) events.capture());
        assertPublishedGroups(events.getValue(), Set.of(10L, 20L));
    }

    @Test
    void publishesOnlyTheExistingGroupWhenAPlanBecomesUnassigned() {
        Fixture fixture = fixture(10L);

        fixture.service().update(PLAN_ID, draft(null, "Updated plan"));

        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(fixture.events()).publishEvent((Object) events.capture());
        assertPublishedGroups(events.getValue(), Set.of(10L));
    }

    @Test
    void doesNotPublishWhenOnlyPlanMetadataChanges() {
        Fixture fixture = fixture(10L);
        when(fixture.groups().existsById(10L)).thenReturn(true);

        fixture.service().update(PLAN_ID, draft(10L, "Renamed plan"));

        verifyNoInteractions(fixture.events());
    }

    private Fixture fixture(Long serverGroupId) {
        ServicePlanRepository plans = mock(ServicePlanRepository.class);
        SubscriptionEntitlementRepository entitlements =
            mock(SubscriptionEntitlementRepository.class);
        NodeAccessGroupRepository groups = mock(NodeAccessGroupRepository.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        ServicePlan plan = ServicePlan.create(
            PLAN_ID,
            "Original plan",
            "Description",
            PlanType.SUBSCRIPTION,
            100L * 1024L * 1024L * 1024L,
            50,
            3,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null,
            false,
            null,
            true,
            true,
            true,
            0,
            List.of("standard"),
            NOW
        );
        plan.assignServerGroup(serverGroupId, NOW);
        plan.addPrice(BillingPeriod.MONTHLY, 2_000L, "CNY");
        when(plans.findById(PLAN_ID)).thenReturn(Optional.of(plan));

        PlanManagementService service = new PlanManagementService(
            plans,
            entitlements,
            groups,
            events,
            CLOCK
        );
        return new Fixture(service, groups, events);
    }

    private void assertPublishedGroups(Object event, Set<Long> expectedGroups) {
        org.assertj.core.api.Assertions.assertThat(event)
            .isInstanceOfSatisfying(
                NodeAccessGroupsChangedEvent.class,
                changed -> {
                    org.assertj.core.api.Assertions.assertThat(changed.groupIds())
                        .isEqualTo(expectedGroups);
                    org.assertj.core.api.Assertions.assertThat(changed.reason())
                        .isEqualTo("plan server group changed");
                }
            );
    }

    private PlanManagementService.PlanDraft draft(
        Long serverGroupId,
        String name
    ) {
        return new PlanManagementService.PlanDraft(
            name,
            "Description",
            PlanType.SUBSCRIPTION,
            Long.toString(100L * 1024L * 1024L * 1024L),
            50,
            3,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null,
            false,
            null,
            true,
            true,
            true,
            0,
            List.of("standard"),
            List.of(new PlanManagementService.PriceDraft(
                BillingPeriod.MONTHLY,
                2_000L,
                "CNY"
            )),
            serverGroupId
        );
    }

    private record Fixture(
        PlanManagementService service,
        NodeAccessGroupRepository groups,
        ApplicationEventPublisher events
    ) {
    }
}
