package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.identity.domain.Role;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.repository.UserAccountRepository;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

import tools.jackson.databind.ObjectMapper;

/** What a settled order does to the account and its subscription. */
class OrderFulfilmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-17T04:00:00Z");
    private static final String TRADE_NO = "SX202609170400000001";

    private ServiceOrderRepository orders;
    private SubscriptionEntitlementRepository entitlements;
    private UserAccountRepository users;
    private OrderFulfilmentService fulfilment;
    private final ArgumentCaptor<SubscriptionEntitlement> saved =
        ArgumentCaptor.forClass(SubscriptionEntitlement.class);

    private UserAccount user;
    private ServicePlan plan;

    @BeforeEach
    void setUp() {
        orders = mock(ServiceOrderRepository.class);
        entitlements = mock(SubscriptionEntitlementRepository.class);
        users = mock(UserAccountRepository.class);
        fulfilment = new OrderFulfilmentService(
            orders,
            entitlements,
            users,
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
        plan = plan("Pro", 5_000);
        when(users.findByIdForUpdate(user.getId()))
            .thenReturn(Optional.of(user));
        when(entitlements.save(any(SubscriptionEntitlement.class)))
            .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void aFirstPurchaseGrantsThePlanForThePeriodBought() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        whenNoSubscription();
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        SubscriptionEntitlement granted = saved();
        assertThat(granted.getPlanId()).isEqualTo(plan.getId());
        assertThat(granted.getExpiresAt())
            .isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
        assertThat(granted.usedBytes()).isZero();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    void aRenewalStacksOntoTheTimeThatWasLeft() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.RENEWAL);
        givenSubscription(entitlement(NOW.plusSeconds(10 * 86_400L)));
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        // The new month starts where the old one ended, not where today is.
        assertThat(saved().getExpiresAt())
            .isEqualTo(Instant.parse("2026-10-27T04:00:00Z"));
    }

    @Test
    void aRenewalKeepsTheTrafficAlreadyUsed() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.RENEWAL);
        SubscriptionEntitlement existing =
            entitlement(NOW.plusSeconds(10 * 86_400L));
        existing.recordUsage(400, 100, NOW);
        givenSubscription(existing);
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        assertThat(saved().usedBytes()).isEqualTo(500);
    }

    @Test
    void anUpgradeForfeitsTheTimeLeftBecauseItsValueWasRefunded() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.UPGRADE);
        givenSubscription(entitlement(NOW.plusSeconds(10 * 86_400L)));
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        assertThat(saved().getExpiresAt())
            .isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
    }

    @Test
    void anUpgradeCreditsTheSurplusAndWritesOffTheOrdersItSpent() {
        ServiceOrder consumedOne = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        ServiceOrder consumedTwo = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        ServiceOrder order = order(
            BillingPeriod.MONTHLY,
            OrderType.UPGRADE,
            breakdown(0, 300),
            idsOf(consumedOne, consumedTwo)
        );
        givenSubscription(entitlement(NOW.plusSeconds(10 * 86_400L)));
        givenOrder(order);
        when(orders.findAllForUpdateById(anyCollection()))
            .thenReturn(List.of(consumedOne, consumedTwo));

        fulfilment.settle(TRADE_NO, "callback-1");

        assertThat(user.getBalanceMinor()).isEqualTo(300);
        assertThat(consumedOne.getStatus()).isEqualTo(OrderStatus.DISCOUNTED);
        assertThat(consumedTwo.getStatus()).isEqualTo(OrderStatus.DISCOUNTED);
    }

    @Test
    void aTrafficPackageReplacesThePlanAndNeverExpires() {
        ServiceOrder order = order(BillingPeriod.ONETIME, OrderType.NEW_PURCHASE);
        SubscriptionEntitlement existing =
            entitlement(NOW.plusSeconds(10 * 86_400L));
        existing.recordUsage(4_900, 100, NOW);
        givenSubscription(existing);
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        SubscriptionEntitlement provisioned = saved();
        assertThat(provisioned.getPlanId()).isEqualTo(plan.getId());
        assertThat(provisioned.getExpiresAt()).isNull();
        assertThat(provisioned.usedBytes()).isZero();
    }

    @Test
    void aTrafficResetZeroesTheUsageAndLeavesTheSubscriptionAlone() {
        ServiceOrder order = order(
            BillingPeriod.RESET_TRAFFIC,
            OrderType.RESET_TRAFFIC
        );
        SubscriptionEntitlement existing =
            entitlement(NOW.plusSeconds(10 * 86_400L));
        existing.recordUsage(4_900, 100, NOW);
        givenSubscription(existing);
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        SubscriptionEntitlement provisioned = saved();
        assertThat(provisioned.usedBytes()).isZero();
        assertThat(provisioned.getExpiresAt())
            .isEqualTo(NOW.plusSeconds(10 * 86_400L));
    }

    @Test
    void aTrafficResetWithNothingToResetFailsInsteadOfHalfApplying() {
        ServiceOrder order = order(
            BillingPeriod.RESET_TRAFFIC,
            OrderType.RESET_TRAFFIC
        );
        whenNoSubscription();
        givenOrder(order);

        assertThatThrownBy(() -> fulfilment.settle(TRADE_NO, "callback-1"))
            .isInstanceOf(IllegalStateException.class);

        // The order was marked paid before provisioning began; the transaction
        // the exception rolls back is what returns it to pending, so nothing is
        // left half-applied in the database.
        assertThat(order.isProcessing()).isTrue();
        assertThat(order.getStatus()).isNotEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void aSecondSettlementChangesNothing() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        givenOrder(order);
        fulfilment.settle(TRADE_NO, "callback-1");
        SubscriptionEntitlement granted = saved();

        fulfilment.settle(TRADE_NO, "callback-1");

        // One grant, not two: a repeated callback must not extend the term.
        verify(entitlements).save(any(SubscriptionEntitlement.class));
        assertThat(granted.getExpiresAt())
            .isEqualTo(Instant.parse("2026-10-17T04:00:00Z"));
    }

    @Test
    void anOrderThatWasAlreadyHandledIsLeftAsItIs() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        order.markPaid("callback-1", NOW);
        order.complete(NOW);
        givenOrder(order);

        fulfilment.settle(TRADE_NO, "callback-1");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void manualSettlementIsRecordedAsSuchAndOpensTheOrder() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        whenNoSubscription();
        givenOrder(order);

        fulfilment.settleManually(TRADE_NO);

        assertThat(order.getCallbackNo())
            .isEqualTo(OrderFulfilmentService.MANUAL_CALLBACK_NO);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void manualSettlementRefusesAnOrderThatIsNotAwaitingPayment() {
        ServiceOrder order = order(BillingPeriod.MONTHLY, OrderType.NEW_PURCHASE);
        order.cancel(NOW);
        givenOrder(order);

        assertThatThrownBy(() -> fulfilment.settleManually(TRADE_NO))
            .isInstanceOf(ApiProblemException.class);

        verifyNoInteractions(entitlements);
        assertThat(user.getBalanceMinor()).isZero();
    }

    @Test
    void anUnreadableConsumedOrderListStopsTheSettlement() {
        ServiceOrder order = order(
            BillingPeriod.MONTHLY,
            OrderType.UPGRADE,
            breakdown(0, 300),
            "not json"
        );
        givenSubscription(entitlement(NOW.plusSeconds(86_400L)));
        givenOrder(order);

        assertThatThrownBy(() -> fulfilment.settle(TRADE_NO, "callback-1"))
            .isInstanceOf(IllegalStateException.class);

        // Refusing to guess matters: reading the list as empty would leave the
        // consumed orders looking settled, and their value could be spent again.
        verify(entitlements, never()).save(any(SubscriptionEntitlement.class));
        assertThat(order.getStatus()).isNotEqualTo(OrderStatus.COMPLETED);
    }

    private void givenOrder(ServiceOrder order) {
        when(orders.findByTradeNoForUpdate(TRADE_NO))
            .thenReturn(Optional.of(order));
    }

    private void givenSubscription(SubscriptionEntitlement entitlement) {
        when(entitlements.findByUserId(user.getId()))
            .thenReturn(Optional.of(entitlement));
    }

    private void whenNoSubscription() {
        when(entitlements.findByUserId(user.getId())).thenReturn(Optional.empty());
    }

    /**
     * The entitlement the settlement handed to the repository. Verifying here
     * pins that exactly one row was written, since a second save would hand the
     * customer a second subscription.
     */
    private SubscriptionEntitlement saved() {
        verify(entitlements).save(saved.capture());
        return saved.getValue();
    }

    private SubscriptionEntitlement entitlement(Instant expiresAt) {
        return SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user,
            plan("Basic", 1_000),
            NOW,
            expiresAt,
            null,
            NOW
        );
    }

    private ServiceOrder order(BillingPeriod period, OrderType type) {
        return order(period, type, breakdown(0, 0), "[]");
    }

    private ServiceOrder order(
        BillingPeriod period,
        OrderType type,
        OrderPricing.Breakdown breakdown,
        String surplusOrderIds
    ) {
        return ServiceOrder.create(
            TRADE_NO,
            user,
            plan,
            period,
            type,
            "CNY",
            breakdown,
            null,
            surplusOrderIds,
            NOW
        );
    }

    /** Surplus credit is what an upgrade hands back to the balance. */
    private OrderPricing.Breakdown breakdown(long total, long surplusCredit) {
        return new OrderPricing.Breakdown(
            5_000, 0, surplusCredit, surplusCredit, 0, total
        );
    }

    private String idsOf(ServiceOrder... sources) {
        return Arrays.stream(sources)
            .map(source -> "\"" + source.getId() + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private ServicePlan plan(String name, long transferLimitBytes) {
        return ServicePlan.create(
            UUID.randomUUID(),
            name,
            name + " plan",
            PlanType.SUBSCRIPTION,
            transferLimitBytes,
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
    }
}
