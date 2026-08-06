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

class SubscriptionEntitlementTest {

    @Test
    void explicitUserGroupOverridesThePlanGroupAndPlanIsTheFallback() {
        Instant now = Instant.parse("2026-08-06T03:00:00Z");
        UserAccount user = UserAccount.register(
            UUID.randomUUID(), "user@example.test", "hash", "User", mock(Role.class), now
        );
        ServicePlan plan = ServicePlan.create(
            UUID.randomUUID(), "Basic", "Basic plan", PlanType.SUBSCRIPTION,
            1_000, 50, 3, TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null, false, null, true, true, true, 0, List.of(), now
        );
        plan.assignServerGroup(10L, now);
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(), user, plan, now, now.plusSeconds(3_600),
            now.plusSeconds(1_800), now
        );

        assertThat(entitlement.getEffectiveServerGroupId()).isEqualTo(10L);

        user.assignServerGroup(20L, now.plusSeconds(1));
        assertThat(entitlement.getEffectiveServerGroupId()).isEqualTo(20L);
    }
}
