package com.sinx.platform.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;

class SubscriptionEntitlementPolicyTest {

    @Test
    void aFreshPaidActivationStartsItsEffectiveCycleEvenWhenReusingAnEntitlement() {
        Instant previousActivation = Instant.parse("2025-01-31T06:00:00Z");
        Instant purchasedAt = Instant.parse("2026-10-06T04:00:00Z");
        ServicePlan inheritedPlan = ServicePlan.create(
            UUID.randomUUID(), "Inherited", "", PlanType.SUBSCRIPTION,
            1_000_000, 100, null, null, false, null, true, true, true,
            0, List.of(), previousActivation
        );
        UserAccount user = UserAccount.register(
            UUID.randomUUID(), "cycle@example.test", "hash", "Cycle",
            mock(Role.class), "subscription-token", previousActivation
        );
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(), user, inheritedPlan, previousActivation,
            purchasedAt.minusSeconds(60), null,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, previousActivation
        );
        entitlement.recordUsage(123L, 456L, purchasedAt.minusSeconds(1));

        entitlement.provisionPeriodic(
            inheritedPlan,
            purchasedAt.plusSeconds(365L * 86_400),
            true,
            TrafficResetPolicy.YEARLY_FROM_ACTIVATION,
            purchasedAt
        );

        assertThat(entitlement.getResetPolicy())
            .isEqualTo(TrafficResetPolicy.YEARLY_FROM_ACTIVATION);
        assertThat(entitlement.getStartsAt()).isEqualTo(purchasedAt);
        assertThat(entitlement.getNextResetAt()).isEqualTo(
            MonthlyResetSchedule.initialBoundary(
                TrafficResetPolicy.YEARLY_FROM_ACTIVATION, purchasedAt
            )
        );
        assertThat(entitlement.usedBytes()).isZero();
    }
}
