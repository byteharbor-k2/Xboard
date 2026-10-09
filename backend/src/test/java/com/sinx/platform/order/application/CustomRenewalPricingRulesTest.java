package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderDeductionMode;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

/** Regression coverage for the user-selected checkout deduction rules. */
class CustomRenewalPricingRulesTest {

    private static final Instant NOW = Instant.parse("2026-06-15T10:00:00Z");
    private final ServicePlanRepository plans = mock(ServicePlanRepository.class);
    private final ServiceOrderRepository orders = mock(ServiceOrderRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final SubscriptionEntitlementRepository entitlements =
        mock(SubscriptionEntitlementRepository.class);
    private final CouponEvaluator coupons = mock(CouponEvaluator.class);
    private final SurplusValuation valuation = mock(SurplusValuation.class);
    private final OrderFulfilmentService fulfilment = mock(OrderFulfilmentService.class);
    private final PlatformConfigurationService configuration =
        mock(PlatformConfigurationService.class);
    private final BalanceLedgerService ledger = mock(BalanceLedgerService.class);
    private final UserAccount user = UserAccount.register(UUID.randomUUID(),
        "buyer@example.test", "hash", "Buyer", mock(Role.class), "token", NOW);
    private ServicePlan plan;
    private ServiceOrder placed;
    private OrderService service;

    @BeforeEach
    void setUp() {
        plan = plan(1_500);
        service = new OrderService(plans, orders, users, entitlements, coupons,
            mock(CouponRedemptionRepository.class), mock(CouponRepository.class),
            valuation, fulfilment, new ObjectMapper(), configuration,
            Clock.fixed(NOW, ZoneOffset.UTC), ledger);
        when(configuration.newUserOfferPlanId()).thenReturn(Optional.empty());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(orders.existsByUserIdAndStatusIn(any(), any())).thenReturn(false);
        when(orders.save(any(ServiceOrder.class))).thenAnswer(call -> {
            placed = call.getArgument(0);
            return placed;
        });
        when(coupons.evaluate(isNull(), any(), any(), any(), anyLong(), any()))
            .thenReturn(Optional.empty());
        when(coupons.evaluate(isNull(), any(), any(), any(), anyLong(), any(),
                org.mockito.ArgumentMatchers.eq(true)))
            .thenReturn(Optional.empty());
        when(entitlements.findByUserId(user.getId()))
            .thenReturn(Optional.empty());
        when(entitlements.findByUserIdForUpdate(user.getId()))
            .thenReturn(Optional.empty());
    }

    @Test
    void fullPaymentDoesNotSpendBalanceOrSurplusAndDefersTheWholeValue() {
        user.creditBalance(2_000, NOW);
        UUID sourceId = UUID.randomUUID();
        SubscriptionEntitlement current = mock(SubscriptionEntitlement.class);
        when(current.isTrial()).thenReturn(false);
        when(current.getPlanId()).thenReturn(UUID.randomUUID());
        when(current.getPlanType()).thenReturn(PlanType.TRAFFIC_PACKAGE);
        when(current.getExpiresAt()).thenReturn(null);
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.of(current));
        when(entitlements.findByUserIdForUpdate(user.getId()))
            .thenReturn(Optional.of(current));
        when(valuation.valueOf(current, NOW)).thenReturn(
            new SurplusValuation.Surplus(700, List.of(sourceId)));

        OrderQuoteView quote = service.quote(user.getId(), plan.getId(),
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);
        ServiceOrder order = service.place(user.getId(), plan.getId(),
            BillingPeriod.MONTHLY, null, OrderDeductionMode.FULL_PAYMENT);

        assertThat(quote.breakdown().surplusAmount()).isZero();
        assertThat(quote.breakdown().balanceAmount()).isZero();
        assertThat(quote.breakdown().totalAmount()).isEqualTo(1_500);
        assertThat(quote.deferredSurplusCreditMinor()).isEqualTo(700);
        assertThat(order.getDeductionMode()).isEqualTo(OrderDeductionMode.FULL_PAYMENT);
        assertThat(order.getDeferredSurplusCreditMinor()).isEqualTo(700);
        assertThat(order.getSurplusOrderIds()).contains(sourceId.toString());
        assertThat(user.getBalanceMinor()).isEqualTo(2_000);
        verify(ledger).debit(user.getId(), 0, order.getTradeNo(), NOW);
        verify(fulfilment, never()).settleCovered(order.getTradeNo());
        verify(orders, never()).findAllForUpdateById(any());
    }

    @Test
    void nonzeroPaymentsAreFlaggedBelowTenAndExactlyTenIsAllowed() {
        plan = plan(999);
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        OrderQuoteView below = service.quote(user.getId(), plan.getId(),
            BillingPeriod.MONTHLY, null);
        assertThat(below.deductionMode()).isEqualTo(OrderDeductionMode.STANDARD);
        assertThat(below.minimumOnlinePaymentBlocked()).isTrue();
        assertThat(below.minimumPaymentMessage())
            .isEqualTo(OrderService.MINIMUM_PAYMENT_MESSAGE);

        ServicePlan exact = plan(1_000);
        when(plans.findById(exact.getId())).thenReturn(Optional.of(exact));
        OrderQuoteView allowed = service.quote(user.getId(), exact.getId(),
            BillingPeriod.MONTHLY, null);
        assertThat(allowed.minimumOnlinePaymentBlocked()).isFalse();
        assertThat(allowed.minimumPaymentMessage()).isNull();

        user.creditBalance(999, NOW);
        OrderQuoteView covered = service.quote(user.getId(), plan.getId(),
            BillingPeriod.MONTHLY, null);
        assertThat(covered.breakdown().totalAmount()).isZero();
        assertThat(covered.minimumOnlinePaymentBlocked()).isFalse();
    }

    @Test
    void expiredHolderCanRepurchaseItsHiddenPlanAndPackageRenewalUsesRemainingValue() {
        ServicePlan hidden = ServicePlan.create(UUID.randomUUID(), "Hidden", "Hidden",
            PlanType.SUBSCRIPTION, 10_000, null,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, 1, false, null,
            false, false, true, 0, List.of(), NOW);
        hidden.addPrice(BillingPeriod.MONTHLY, 1_000, "CNY");
        SubscriptionEntitlement expiredHolder = mock(SubscriptionEntitlement.class);
        when(expiredHolder.isTrial()).thenReturn(false);
        when(expiredHolder.getPlanId()).thenReturn(hidden.getId());
        when(expiredHolder.getExpiresAt()).thenReturn(NOW.minusSeconds(1));
        when(entitlements.findByUserId(user.getId()))
            .thenReturn(Optional.of(expiredHolder));
        when(entitlements.findByUserIdForUpdate(user.getId()))
            .thenReturn(Optional.of(expiredHolder));
        when(plans.findById(hidden.getId())).thenReturn(Optional.of(hidden));

        ServiceOrder repurchase = service.place(user.getId(), hidden.getId(),
            BillingPeriod.MONTHLY, null);

        assertThat(repurchase.getOrderType()).isEqualTo(
            com.sinx.platform.order.domain.OrderType.NEW_PURCHASE);
        assertThat(repurchase.getSurplusAmount()).isZero();
        assertThat(repurchase.getStatus()).isEqualTo(OrderStatus.PENDING);

        ServicePlan packagePlan = trafficPackage(1_000);
        SubscriptionEntitlement packageHolder = mock(SubscriptionEntitlement.class);
        when(packageHolder.isTrial()).thenReturn(false);
        when(packageHolder.getPlanId()).thenReturn(packagePlan.getId());
        when(packageHolder.getPlanType()).thenReturn(PlanType.TRAFFIC_PACKAGE);
        when(packageHolder.getExpiresAt()).thenReturn(null);
        when(entitlements.findByUserId(user.getId()))
            .thenReturn(Optional.of(packageHolder));
        when(plans.findById(packagePlan.getId())).thenReturn(Optional.of(packagePlan));
        when(valuation.valueOf(packageHolder, NOW)).thenReturn(
            new SurplusValuation.Surplus(300, List.of(UUID.randomUUID())));

        OrderQuoteView packageRenewal = service.quote(user.getId(), packagePlan.getId(),
            BillingPeriod.ONETIME, null);
        assertThat(packageRenewal.orderType()).isEqualTo(
            com.sinx.platform.order.domain.OrderType.RENEWAL);
        assertThat(packageRenewal.breakdown().surplusAmount()).isEqualTo(300);
        assertThat(packageRenewal.breakdown().totalAmount()).isEqualTo(700);
    }

    private ServicePlan plan(long amount) {
        ServicePlan result = ServicePlan.create(UUID.randomUUID(), "Plan", "Plan",
            PlanType.SUBSCRIPTION, 10_000, null,
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, null, false, null,
            true, true, true, 0, List.of(), NOW);
        result.addPrice(BillingPeriod.MONTHLY, amount, "CNY");
        return result;
    }

    private ServicePlan trafficPackage(long amount) {
        ServicePlan result = ServicePlan.create(UUID.randomUUID(), "Traffic", "Traffic",
            PlanType.TRAFFIC_PACKAGE, 10_000, null, TrafficResetPolicy.NEVER,
            null, false, null, true, true, true, 0, List.of(), NOW);
        result.addPrice(BillingPeriod.ONETIME, amount, "CNY");
        return result;
    }
}
