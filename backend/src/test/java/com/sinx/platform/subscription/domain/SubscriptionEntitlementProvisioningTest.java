package com.sinx.platform.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

/** How a paid order lands on the subscription it bought. */
class SubscriptionEntitlementProvisioningTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");

    @Test
    void aPeriodicPlanTakesItsLimitsFromThePlanAndKeepsTheCounters() {
        UserAccount user = user();
        SubscriptionEntitlement entitlement = entitlement(
            user, plan("Basic", 1_000, 50, 3)
        );
        entitlement.recordUsage(400, 100, NOW);

        entitlement.provisionPeriodic(
            plan("Pro", 5_000, 200, 8),
            NOW.plusSeconds(86_400),
            false,
            NOW
        );

        assertThat(entitlement.getPlanName()).isEqualTo("Pro");
        assertThat(entitlement.getTransferLimitBytes()).isEqualTo(5_000);
        assertThat(entitlement.getSpeedLimitMbps()).isEqualTo(200);
        assertThat(entitlement.getDeviceLimit()).isEqualTo(8);
        assertThat(entitlement.getExpiresAt()).isEqualTo(NOW.plusSeconds(86_400));
        assertThat(entitlement.usedBytes()).isEqualTo(500);
    }

    @Test
    void startingFreshZeroesTheCounters() {
        UserAccount user = user();
        SubscriptionEntitlement entitlement = entitlement(
            user, plan("Basic", 1_000, 50, 3)
        );
        entitlement.recordUsage(400, 100, NOW);

        entitlement.provisionPeriodic(
            plan("Pro", 5_000, 200, 8),
            NOW.plusSeconds(86_400),
            true,
            NOW
        );

        assertThat(entitlement.usedBytes()).isZero();
        assertThat(entitlement.remainingBytes()).isEqualTo(5_000);
    }

    @Test
    void aPackageReplacesThePlanAndDropsTheExpiry() {
        UserAccount user = user();
        SubscriptionEntitlement entitlement = entitlement(
            user, plan("Basic", 1_000, 50, 3)
        );
        entitlement.recordUsage(900, 100, NOW);

        entitlement.provisionPackage(plan("Top-up", 20_000, null, null), NOW);

        assertThat(entitlement.getPlanName()).isEqualTo("Top-up");
        assertThat(entitlement.getTransferLimitBytes()).isEqualTo(20_000);
        assertThat(entitlement.getExpiresAt()).isNull();
        assertThat(entitlement.usedBytes()).isZero();
    }

    @Test
    void aTrafficResetClearsTheCountersAndNothingElse() {
        UserAccount user = user();
        SubscriptionEntitlement entitlement = entitlement(
            user, plan("Basic", 1_000, 50, 3)
        );
        entitlement.recordUsage(900, 100, NOW);
        Instant expiry = entitlement.getExpiresAt();

        entitlement.resetTraffic(NOW);

        assertThat(entitlement.usedBytes()).isZero();
        assertThat(entitlement.getPlanName()).isEqualTo("Basic");
        assertThat(entitlement.getExpiresAt()).isEqualTo(expiry);
    }

    @Test
    void aPeriodicPlanMustEndAtSomePoint() {
        SubscriptionEntitlement entitlement = entitlement(user(), plan("Basic", 1_000, 50, 3));

        assertThatThrownBy(() ->
            entitlement.provisionPeriodic(plan("Basic", 1_000, 50, 3), null, false, NOW)
        ).isInstanceOf(IllegalArgumentException.class);
    }

    private SubscriptionEntitlement entitlement(
        UserAccount user,
        ServicePlan plan
    ) {
        return SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user,
            plan,
            NOW,
            NOW.plusSeconds(86_400),
            null,
            NOW
        );
    }

    private ServicePlan plan(
        String name,
        long transferLimitBytes,
        Integer speedLimitMbps,
        Integer deviceLimit
    ) {
        return ServicePlan.create(
            UUID.randomUUID(),
            name,
            name + " plan",
            PlanType.SUBSCRIPTION,
            transferLimitBytes,
            speedLimitMbps,
            deviceLimit,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null,
            false,
            null,
            true,
            true,
            true,
            0,
            List.of(),
            NOW
        );
    }

    private UserAccount user() {
        return UserAccount.register(
            UUID.randomUUID(),
            "user@example.test",
            "hash",
            "User",
            mock(Role.class),
            "subscription-token",
            NOW
        );
    }
}
