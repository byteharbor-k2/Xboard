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

class SubscriptionEntitlementTrialPurchaseTest {

    private static final Instant PURCHASED_AT =
        Instant.parse("2026-09-17T04:00:00Z");
    private static final Instant TRIAL_STARTED_AT =
        PURCHASED_AT.minusSeconds(14L * 24 * 60 * 60);

    @Test
    void delayedTrialPurchaseStartsANewMonthlyResetCycle() {
        SubscriptionEntitlement entitlement = trialEntitlement();
        Instant previousBoundary = entitlement.getNextResetAt();
        ServicePlan paidPlan = plan(
            "Paid monthly",
            PlanType.SUBSCRIPTION,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
        );

        entitlement.provisionPeriodic(
            paidPlan,
            PURCHASED_AT.plusSeconds(30L * 24 * 60 * 60),
            true,
            PURCHASED_AT
        );
        entitlement.markPurchased(PURCHASED_AT);

        assertThat(entitlement.isTrial()).isFalse();
        assertThat(entitlement.getStartsAt()).isEqualTo(PURCHASED_AT);
        assertThat(entitlement.getNextResetAt())
            .isEqualTo(MonthlyResetSchedule.initialBoundary(PURCHASED_AT))
            .isNotEqualTo(previousBoundary);
        assertThat(entitlement.getUpdatedAt()).isEqualTo(PURCHASED_AT);
    }

    @Test
    void trialPurchaseOfTrafficPackageClearsMonthlyResetBoundary() {
        SubscriptionEntitlement entitlement = trialEntitlement();
        ServicePlan packagePlan = plan(
            "Traffic package",
            PlanType.TRAFFIC_PACKAGE,
            TrafficResetPolicy.NEVER
        );

        entitlement.provisionPackage(packagePlan, PURCHASED_AT);
        entitlement.markPurchased(PURCHASED_AT);

        assertThat(entitlement.isTrial()).isFalse();
        assertThat(entitlement.getStartsAt()).isEqualTo(PURCHASED_AT);
        assertThat(entitlement.getNextResetAt()).isNull();
        assertThat(entitlement.getUpdatedAt()).isEqualTo(PURCHASED_AT);
    }

    @Test
    void paidMonthlyRenewalKeepsItsExistingResetAnchor() {
        ServicePlan initialPlan = plan(
            "Initial paid plan",
            PlanType.SUBSCRIPTION,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
        );
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user(),
            initialPlan,
            TRIAL_STARTED_AT,
            PURCHASED_AT.plusSeconds(10L * 24 * 60 * 60),
            null,
            TRIAL_STARTED_AT
        );
        Instant originalStart = entitlement.getStartsAt();
        Instant originalBoundary = entitlement.getNextResetAt();

        entitlement.provisionPeriodic(
            plan(
                "Renewed paid plan",
                PlanType.SUBSCRIPTION,
                TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
            ),
            PURCHASED_AT.plusSeconds(40L * 24 * 60 * 60),
            false,
            PURCHASED_AT
        );
        entitlement.markPurchased(PURCHASED_AT);

        assertThat(entitlement.isTrial()).isFalse();
        assertThat(entitlement.getStartsAt()).isEqualTo(originalStart);
        assertThat(entitlement.getNextResetAt()).isEqualTo(originalBoundary);
    }

    private SubscriptionEntitlement trialEntitlement() {
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user(),
            plan(
                "Registration trial",
                PlanType.SUBSCRIPTION,
                TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
            ),
            TRIAL_STARTED_AT,
            TRIAL_STARTED_AT.plusSeconds(3 * 60 * 60),
            null,
            TRIAL_STARTED_AT
        );
        entitlement.markTrial(TRIAL_STARTED_AT);
        return entitlement;
    }

    private ServicePlan plan(
        String name,
        PlanType planType,
        TrafficResetPolicy resetPolicy
    ) {
        boolean subscription = planType == PlanType.SUBSCRIPTION;
        return ServicePlan.create(
            UUID.randomUUID(),
            name,
            name,
            planType,
            1_000_000,
            100,
            resetPolicy,
            null,
            false,
            null,
            true,
            true,
            subscription,
            0,
            List.of(),
            TRIAL_STARTED_AT
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
            TRIAL_STARTED_AT
        );
    }
}
