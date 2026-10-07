package com.sinx.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

class HolderOnlyPlanOfferTest {

    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00Z");
    private final ServicePlanRepository plans = mock(ServicePlanRepository.class);
    private final SubscriptionEntitlementRepository entitlements =
        mock(SubscriptionEntitlementRepository.class);
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final PlatformConfigurationService configuration =
        mock(PlatformConfigurationService.class);
    private CatalogService catalog;
    private ServicePlan hidden;

    @BeforeEach
    void setUp() {
        catalog = new CatalogService(plans, entitlements, orders, configuration,
            Clock.fixed(NOW, ZoneOffset.UTC));
        hidden = ServicePlan.create(UUID.randomUUID(), "Private plan", "Details",
            PlanType.SUBSCRIPTION, 10_000, null,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, 1, false, null,
            false, false, true, 0, List.of(), NOW);
        hidden.addPrice(BillingPeriod.MONTHLY, 1_000, "CNY");
        when(configuration.newUserOfferPlanId()).thenReturn(Optional.empty());
        when(configuration.globalTrafficResetPolicy())
            .thenReturn(TrafficResetPolicy.MONTHLY_FROM_ACTIVATION);
        when(plans.findById(hidden.getId())).thenReturn(Optional.of(hidden));
        when(entitlements.countActiveForPlan(hidden.getId(), NOW)).thenReturn(1L);
    }

    @Test
    void currentNontrialHolderCanReadHiddenFullCapacityOfferEvenAfterExpiry() {
        UUID ownerId = UUID.randomUUID();
        SubscriptionEntitlement holder = mock(SubscriptionEntitlement.class);
        when(holder.isTrial()).thenReturn(false);
        when(holder.getPlanId()).thenReturn(hidden.getId());
        when(holder.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(entitlements.findByUserId(ownerId)).thenReturn(Optional.of(holder));

        PlanOfferView offer = catalog.availableOffer(hidden.getId(), ownerId)
            .orElseThrow();

        assertThat(offer.id()).isEqualTo(hidden.getId());
        assertThat(offer.capacityRemaining()).isZero();
        assertThat(offer.prices()).hasSize(1);
    }

    @Test
    void anonymousUnrelatedAndTrialViewersCannotReadTheHiddenOffer() {
        assertThat(catalog.availableOffer(hidden.getId(), null)).isEmpty();

        UUID unrelatedId = UUID.randomUUID();
        SubscriptionEntitlement unrelated = mock(SubscriptionEntitlement.class);
        when(unrelated.isTrial()).thenReturn(false);
        when(unrelated.getPlanId()).thenReturn(UUID.randomUUID());
        when(entitlements.findByUserId(unrelatedId)).thenReturn(Optional.of(unrelated));
        assertThat(catalog.availableOffer(hidden.getId(), unrelatedId)).isEmpty();

        UUID trialId = UUID.randomUUID();
        SubscriptionEntitlement trial = mock(SubscriptionEntitlement.class);
        when(trial.isTrial()).thenReturn(true);
        when(trial.getPlanId()).thenReturn(hidden.getId());
        when(entitlements.findByUserId(trialId)).thenReturn(Optional.of(trial));
        assertThat(catalog.availableOffer(hidden.getId(), trialId)).isEmpty();
    }
}
