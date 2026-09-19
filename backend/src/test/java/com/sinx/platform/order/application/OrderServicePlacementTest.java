package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.CouponRedemptionRepository;
import com.sinx.platform.order.repository.CouponRepository;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * What placing an order produces - in particular an order the balance covered
 * in full, which is opened straight away because nothing is left to collect.
 */
class OrderServicePlacementTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");

    private ServicePlanRepository plans;
    private ServiceOrderRepository orders;
    private UserAccountRepository users;
    private SubscriptionEntitlementRepository entitlements;
    private OrderFulfilmentService fulfilment;
    private OrderService service;

    /** The order the repository last accepted, so settlement can find it. */
    private final AtomicReference<ServiceOrder> saved = new AtomicReference<>();

    private UserAccount user;
    private ServicePlan plan;

    @BeforeEach
    void setUp() {
        plans = mock(ServicePlanRepository.class);
        orders = mock(ServiceOrderRepository.class);
        users = mock(UserAccountRepository.class);
        entitlements = mock(SubscriptionEntitlementRepository.class);
        fulfilment = mock(OrderFulfilmentService.class);
        service = new OrderService(
            plans,
            orders,
            users,
            entitlements,
            mock(CouponEvaluator.class),
            mock(CouponRedemptionRepository.class),
            mock(CouponRepository.class),
            mock(SurplusValuation.class),
            fulfilment,
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        user = UserAccount.register(
            UUID.randomUUID(),
            "user@example.test",
            "hash",
            "User",
            mock(Role.class),
            "subscription-token",
            NOW
        );
        plan = plan();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(orders.existsByUserIdAndStatusIn(any(), any())).thenReturn(false);
        when(orders.save(any(ServiceOrder.class))).thenAnswer(call -> {
            ServiceOrder placed = call.getArgument(0);
            saved.set(placed);
            return placed;
        });
        // The real settlement opens the order; here it marks the very order
        // place() just saved, which is the one it hands back.
        when(fulfilment.settleFromBalance(anyString())).thenAnswer(call -> {
            ServiceOrder placed = saved.get();
            placed.markPaid(OrderFulfilmentService.BALANCE_CALLBACK_NO, NOW);
            placed.complete(NOW);
            return placed;
        });
    }

    @Test
    void anOrderTheBalanceCoversCompletelyIsSettledStraightAway() {
        // 10.00 plan, 50.00 balance: the account owes nothing. The balance is
        // money already received, so the order is handed to settlement rather
        // than parked in a state where the gateway list is empty and the
        // customer can neither pay it nor have it opened.
        user.creditBalance(5_000, NOW);

        ServiceOrder order = service.place(
            user.getId(),
            plan.getId(),
            BillingPeriod.MONTHLY,
            null
        );

        // The balance was still taken at placement, before anything was opened.
        assertThat(user.getBalanceMinor()).isEqualTo(4_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getCallbackNo())
            .isEqualTo(OrderFulfilmentService.BALANCE_CALLBACK_NO);
        verify(fulfilment).settleFromBalance(order.getTradeNo());
    }

    @Test
    void anOrderThatIsStillPayableWaitsForItsPayment() {
        ServiceOrder order = service.place(
            user.getId(),
            plan.getId(),
            BillingPeriod.MONTHLY,
            null
        );

        assertThat(order.getTotalAmount()).isEqualTo(1_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        verify(fulfilment, never()).settleFromBalance(anyString());
    }

    private ServicePlan plan() {
        ServicePlan created = ServicePlan.create(
            UUID.randomUUID(),
            "Pro",
            "Pro plan",
            PlanType.SUBSCRIPTION,
            5_000,
            50,
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
        created.addPrice(BillingPeriod.MONTHLY, 1_000, "CNY");
        return created;
    }
}
