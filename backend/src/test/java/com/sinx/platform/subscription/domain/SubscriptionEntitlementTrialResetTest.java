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

class SubscriptionEntitlementTrialResetTest {

    private static final Instant ACTIVATED_AT =
        Instant.parse("2026-08-17T04:00:00Z");

    @Test
    void trialHasNoMonthlyBoundaryEvenAfterItExpires() {
        SubscriptionEntitlement trial = trialEntitlement(
            monthlyPlan("Trial plan"),
            ACTIVATED_AT.plusSeconds(3 * 60 * 60)
        );

        assertThat(trial.isTrial()).isTrue();
        assertThat(trial.getResetPolicy())
            .isEqualTo(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
        assertThat(trial.getNextResetAt()).isNull();
        assertThat(trial.stateAt(ACTIVATED_AT.plusSeconds(3 * 60 * 60)))
            .isEqualTo(EntitlementState.EXPIRED);
        assertThat(trial.getNextResetAt()).isNull();
    }

    @Test
    void paidMonthlyConversionStartsThePurchasedPlanCycleAtPurchaseTime() {
        SubscriptionEntitlement trial = trialEntitlement(
            monthlyPlan("Trial plan"),
            ACTIVATED_AT.plusSeconds(3 * 60 * 60)
        );
        Instant purchasedAt = ACTIVATED_AT.plusSeconds(8 * 60 * 60);
        ServicePlan paidPlan = monthlyPlan("Paid plan");

        trial.provisionPeriodic(
            paidPlan,
            purchasedAt.plusSeconds(30L * 24 * 60 * 60),
            true,
            purchasedAt
        );
        trial.markPurchased(purchasedAt);

        assertThat(trial.isTrial()).isFalse();
        assertThat(trial.getResetPolicy())
            .isEqualTo(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
        assertThat(trial.getStartsAt()).isEqualTo(purchasedAt);
        assertThat(trial.getNextResetAt())
            .isEqualTo(MonthlyResetSchedule.initialBoundary(purchasedAt));
    }

    private SubscriptionEntitlement trialEntitlement(
        ServicePlan plan,
        Instant expiresAt
    ) {
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user(),
            plan,
            ACTIVATED_AT,
            expiresAt,
            null,
            ACTIVATED_AT
        );
        entitlement.markTrial(ACTIVATED_AT);
        return entitlement;
    }

    private ServicePlan monthlyPlan(String name) {
        return ServicePlan.create(
            UUID.randomUUID(),
            name,
            name,
            PlanType.SUBSCRIPTION,
            4_000_000,
            100,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null,
            false,
            null,
            true,
            true,
            true,
            0,
            List.of(),
            ACTIVATED_AT
        );
    }

    private UserAccount user() {
        return UserAccount.register(
            UUID.randomUUID(),
            "trial-reset@example.test",
            "hash",
            "Trial user",
            mock(Role.class),
            "subscription-token",
            ACTIVATED_AT
        );
    }
}
