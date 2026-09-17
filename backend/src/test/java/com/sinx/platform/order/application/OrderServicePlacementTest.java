package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * What placing an order produces - in particular an order the deductions
 * covered in full, which is still an order nobody has paid for.
 */
class OrderServicePlacementTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");

    private ServicePlanRepository plans;
    private ServiceOrderRepository orders;
    private UserAccountRepository users;
    private SubscriptionEntitlementRepository entitlements;
    private OrderService service;

    private UserAccount user;
    private ServicePlan plan;

    @BeforeEach
    void setUp() {
        plans = mock(ServicePlanRepository.class);
        orders = mock(ServiceOrderRepository.class);
        users = mock(UserAccountRepository.class);
        entitlements = mock(SubscriptionEntitlementRepository.class);
        service = new OrderService(
            plans,
            orders,
            users,
            entitlements,
            mock(CouponEvaluator.class),
            mock(CouponRedemptionRepository.class),
            mock(CouponRepository.class),
            mock(SurplusValuation.class),
            new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
        user = UserAccount.register(
            UUID.randomUUID(),
            "user@example.test",
            "hash",
            "User",
            mock(Role.class),
            NOW
        );
        plan = plan();
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(plans.findById(plan.getId())).thenReturn(Optional.of(plan));
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(orders.existsByUserIdAndStatusIn(any(), any())).thenReturn(false);
        when(orders.save(any(ServiceOrder.class)))
            .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void anOrderTheBalanceCoversCompletelyStillWaitsToBePaid() {
        // 10.00 plan, 50.00 balance: the account owes nothing, and the original
        // panel would have opened the subscription straight from checkout.
        user.creditBalance(5_000, NOW);

        ServiceOrder order = service.place(
            user.getId(),
            plan.getId(),
            BillingPeriod.MONTHLY,
            null
        );

        assertThat(order.getTotalAmount()).isZero();
        assertThat(order.getBalanceAmount()).isEqualTo(1_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getCallbackNo()).isNull();
        assertThat(user.getBalanceMinor()).isEqualTo(4_000);
    }

    @Test
    void placingAnOrderProvisionsNothingOnItsOwn() {
        user.creditBalance(5_000, NOW);

        service.place(user.getId(), plan.getId(), BillingPeriod.MONTHLY, null);

        // Granting the subscription is the settlement's job, so an order that
        // owes nothing waits for one exactly like any other.
        verify(entitlements, never()).save(any(SubscriptionEntitlement.class));
    }

    @Test
    void anOrderThatIsStillPayableAlsoWaitsToBePaid() {
        ServiceOrder order = service.place(
            user.getId(),
            plan.getId(),
            BillingPeriod.MONTHLY,
            null
        );

        assertThat(order.getTotalAmount()).isEqualTo(1_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    private ServicePlan plan() {
        ServicePlan created = ServicePlan.create(
            UUID.randomUUID(),
            "Pro",
            "Pro plan",
            PlanType.SUBSCRIPTION,
            5_000,
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
            List.of(),
            NOW
        );
        created.addPrice(BillingPeriod.MONTHLY, 1_000, "CNY");
        return created;
    }
}
