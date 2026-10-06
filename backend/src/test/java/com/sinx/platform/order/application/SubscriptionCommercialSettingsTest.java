package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

/** The subscription switches gate both customer-facing pricing paths identically. */
class SubscriptionCommercialSettingsTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID SOURCE_ORDER = UUID.fromString(
        "00000000-0000-0000-0000-000000000111"
    );
    private ServicePlanRepository plans;
    private ServiceOrderRepository orders;
    private UserAccountRepository users;
    private SubscriptionEntitlementRepository entitlements;
    private CouponEvaluator coupons;
    private SurplusValuation surplus;
    private OrderFulfilmentService fulfilment;
    private PlatformConfigurationService configuration;
    private OrderService service;
    private UserAccount user;
    private ServicePlan currentPlan;
    private ServicePlan targetPlan;

    @BeforeEach
    void setUp() {
        plans = mock(ServicePlanRepository.class);
        orders = mock(ServiceOrderRepository.class);
        users = mock(UserAccountRepository.class);
        entitlements = mock(SubscriptionEntitlementRepository.class);
        coupons = mock(CouponEvaluator.class);
        surplus = mock(SurplusValuation.class);
        fulfilment = mock(OrderFulfilmentService.class);
        configuration = mock(PlatformConfigurationService.class);
        service = new OrderService(
            plans,
            orders,
            users,
            entitlements,
            coupons,
            mock(CouponRedemptionRepository.class),
            mock(CouponRepository.class),
            surplus,
            fulfilment,
            new ObjectMapper(),
            configuration,
            CLOCK
        );
        user = UserAccount.register(
            UUID.randomUUID(), "buyer@example.test", "hash", "Buyer",
            mock(Role.class), "subscription-token", NOW.minusSeconds(60)
        );
        currentPlan = plan("Current", PlanType.SUBSCRIPTION, false);
        targetPlan = plan("Target", PlanType.SUBSCRIPTION, false);
        currentPlan.addPrice(BillingPeriod.MONTHLY, 2_000, "CNY");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(plans.findById(currentPlan.getId())).thenReturn(Optional.of(currentPlan));
        when(plans.findById(targetPlan.getId())).thenReturn(Optional.of(targetPlan));
        when(orders.existsByUserIdAndStatusIn(any(), any())).thenReturn(false);
        when(configuration.newUserOfferPlanId()).thenReturn(Optional.empty());
        when(configuration.subscriptionPolicy()).thenReturn(policy(true, true));
        when(coupons.evaluate(
            any(), any(), any(), any(), anyLong(), any(), anyBoolean()
        )).thenReturn(Optional.empty());
        when(coupons.evaluate(
            any(), any(), any(), any(), anyLong(), any()
        )).thenReturn(Optional.empty());
        when(orders.save(any(ServiceOrder.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void disabledPlanChangesRejectQuotesAndPlacementsButAllowRenewalsTrialsAndExpiredAccounts() {
        when(configuration.subscriptionPolicy()).thenReturn(policy(false, true));
        SubscriptionEntitlement active = entitlement(currentPlan, false, NOW.plusSeconds(3600));
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(active));

        assertRejectedQuoteAndPlacement(targetPlan, BillingPeriod.MONTHLY);

        // Same-plan renewal is not a plan change.
        assertThat(service.quote(user.getId(), currentPlan.getId(), BillingPeriod.MONTHLY, null)
            .orderType()).isEqualTo(OrderType.RENEWAL);

        // A trial and an expired entitlement are both classified as a new purchase.
        active.markTrial(NOW.minusSeconds(1));
        assertThat(service.quote(user.getId(), targetPlan.getId(), BillingPeriod.MONTHLY, null)
            .orderType()).isEqualTo(OrderType.NEW_PURCHASE);

        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.empty());
        assertThat(service.quote(user.getId(), targetPlan.getId(), BillingPeriod.MONTHLY, null)
            .orderType()).isEqualTo(OrderType.NEW_PURCHASE);
        active = entitlement(currentPlan, false, NOW.minusSeconds(1));
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(active));
        assertThat(service.quote(user.getId(), targetPlan.getId(), BillingPeriod.MONTHLY, null)
            .orderType()).isEqualTo(OrderType.NEW_PURCHASE);

        // Reset purchases do not change plans and remain available.
        currentPlan = plan("Resettable", PlanType.SUBSCRIPTION, true);
        when(plans.findById(currentPlan.getId())).thenReturn(Optional.of(currentPlan));
        SubscriptionEntitlement resetEntitlement = entitlement(
            currentPlan, false, NOW.plusSeconds(3600)
        );
        when(entitlements.findByUserId(user.getId()))
            .thenReturn(Optional.of(resetEntitlement));
        assertThat(service.quote(
            user.getId(), currentPlan.getId(), BillingPeriod.RESET_TRAFFIC, null
        ).orderType()).isEqualTo(OrderType.RESET_TRAFFIC);
    }

    @Test
    void activePermanentPackageToDifferentPlanIsAlsoBlockedWhenPlanChangesAreDisabled() {
        when(configuration.subscriptionPolicy()).thenReturn(policy(false, true));
        currentPlan = plan("Permanent", PlanType.TRAFFIC_PACKAGE, false);
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(
            entitlement(currentPlan, false, null)
        ));

        assertRejectedQuoteAndPlacement(targetPlan, BillingPeriod.MONTHLY);
    }

    @Test
    void disabledSurplusSkipsValuationAndCreatesFullPriceUpgradeWithoutSourceOrders() {
        when(configuration.subscriptionPolicy()).thenReturn(policy(true, false));
        ServicePlan packagePlan = plan("Bucket", PlanType.TRAFFIC_PACKAGE, false);
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(
            entitlement(packagePlan, false, null)
        ));

        ServiceOrder order = service.place(
            user.getId(), targetPlan.getId(), BillingPeriod.MONTHLY, null
        );

        assertThat(order.getOrderType()).isEqualTo(OrderType.UPGRADE);
        assertThat(order.getOriginalAmount()).isEqualTo(1_000);
        assertThat(order.getSurplusAmount()).isZero();
        assertThat(order.getSurplusCredit()).isZero();
        assertThat(order.getTotalAmount()).isEqualTo(1_000);
        assertThat(order.getSurplusOrderIds()).isEqualTo("[]");
        verify(surplus, never()).valueOf(any(), any());
    }

    @Test
    void alreadyPlacedUpgradeKeepsItsPricingSnapshotAfterSurplusIsDisabled() {
        SubscriptionEntitlement active = entitlement(
            currentPlan, false, NOW.plusSeconds(86_400)
        );
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(active));
        when(surplus.valueOf(active, NOW)).thenReturn(
            new SurplusValuation.Surplus(200, List.of(SOURCE_ORDER))
        );

        ServiceOrder placed = service.place(
            user.getId(), targetPlan.getId(), BillingPeriod.MONTHLY, null
        );
        when(configuration.subscriptionPolicy()).thenReturn(policy(true, false));

        assertThat(placed.getSurplusAmount()).isEqualTo(200);
        assertThat(placed.getTotalAmount()).isEqualTo(800);
        assertThat(placed.getSurplusOrderIds()).contains(SOURCE_ORDER.toString());
        verify(surplus).valueOf(active, NOW);
    }

    private void assertRejectedQuoteAndPlacement(
        ServicePlan target,
        BillingPeriod period
    ) {
        assertThatThrownBy(() -> service.quote(
            user.getId(), target.getId(), period, null
        )).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> service.place(
            user.getId(), target.getId(), period, null
        )).isInstanceOf(ApiProblemException.class);
        verify(orders, never()).save(any(ServiceOrder.class));
        verify(surplus, never()).valueOf(any(), any());
    }

    private PlatformConfigurationService.SubscriptionPolicy policy(
        boolean planChange,
        boolean surplusEnabled
    ) {
        return new PlatformConfigurationService.SubscriptionPolicy(
            planChange, surplusEnabled, 1
        );
    }

    private SubscriptionEntitlement entitlement(
        ServicePlan plan,
        boolean trial,
        Instant expiresAt
    ) {
        SubscriptionEntitlement value = SubscriptionEntitlement.grant(
            UUID.randomUUID(), user, plan, NOW.minusSeconds(3600), expiresAt,
            null, plan.getResetPolicy() == null
                ? TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
                : plan.getResetPolicy(), NOW.minusSeconds(3600)
        );
        if (trial) {
            value.markTrial(NOW.minusSeconds(1));
        }
        return value;
    }

    private ServicePlan plan(String name, PlanType type, boolean resettable) {
        ServicePlan plan = ServicePlan.create(
            UUID.randomUUID(), name, name, type, 1_000_000, 50,
            type == PlanType.TRAFFIC_PACKAGE
                ? TrafficResetPolicy.NEVER
                : TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            null, resettable, null, true, true, true, 0, List.of(), NOW
        );
        plan.addPrice(
            type == PlanType.TRAFFIC_PACKAGE
                ? BillingPeriod.ONETIME : BillingPeriod.MONTHLY,
            1_000, "CNY"
        );
        if (resettable) {
            plan.addPrice(BillingPeriod.RESET_TRAFFIC, 100, "CNY");
        }
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        return plan;
    }
}
