package com.sinx.platform.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

class SurplusValuationTest {

    private static final long GIB = 1_073_741_824L;
    private static final UUID USER_ID =
        UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final ServiceOrderRepository orders =
        mock(ServiceOrderRepository.class);
    private final SurplusValuation valuation = new SurplusValuation(orders);

    @Test
    void valuesNothingWhenTheCustomerHoldsNoEntitlement() {
        assertThat(valuation.valueOf(null, Instant.now()).amountMinor())
            .isZero();
    }

    @Test
    void pricesAPackageByTheTrafficLeftInIt() {
        // 100 GiB bought for 40.00, 25 GiB used -> three quarters remain.
        SubscriptionEntitlement entitlement =
            trafficPackage(100 * GIB, 20 * GIB, 5 * GIB);
        ServiceOrder purchase = settledOrder(
            BillingPeriod.ONETIME,
            Instant.parse("2026-01-01T00:00:00Z"),
            3500,
            500
        );
        when(orders.findLatestSettledForPeriod(
            USER_ID,
            OrderStatus.COMPLETED,
            BillingPeriod.ONETIME
        )).thenReturn(Optional.of(purchase));
        when(orders.findSettledOrderIdsExcludingPeriod(
            any(),
            any(),
            any()
        )).thenReturn(List.of());

        assertThat(valuation.valueOf(entitlement, Instant.now()).amountMinor())
            .isEqualTo(3000);
    }

    @Test
    void valuesAnExhaustedPackageAtNothing() {
        SubscriptionEntitlement entitlement =
            trafficPackage(100 * GIB, 100 * GIB, 0);
        ServiceOrder purchase = settledOrder(
            BillingPeriod.ONETIME,
            Instant.parse("2026-01-01T00:00:00Z"),
            3500,
            0
        );
        when(orders.findLatestSettledForPeriod(any(), any(), any()))
            .thenReturn(Optional.of(purchase));

        assertThat(valuation.valueOf(entitlement, Instant.now()).amountMinor())
            .isZero();
    }

    @Test
    void pricesASubscriptionByTheUnelapsedShareOfWhatWasPaid() {
        Instant openedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-01-17T00:00:00Z");
        SubscriptionEntitlement entitlement =
            periodicSubscription(Instant.parse("2026-02-01T00:00:00Z"));
        List<ServiceOrder> history =
            List.of(settledOrder(BillingPeriod.MONTHLY, openedAt, 3500, 0));
        when(orders.findSettledPeriodicOrders(
            any(),
            any(),
            anyCollection()
        )).thenReturn(history);

        // 15 of the 31 days paid for remain: 3500 * 1296000/2678400, truncated.
        assertThat(valuation.valueOf(entitlement, now).amountMinor())
            .isEqualTo(1693);
    }

    @Test
    void valuesASubscriptionAtNothingOnceItsPaidWindowHasElapsed() {
        Instant openedAt = Instant.parse("2026-01-01T00:00:00Z");
        SubscriptionEntitlement entitlement =
            periodicSubscription(Instant.parse("2026-02-01T00:00:00Z"));
        List<ServiceOrder> history =
            List.of(settledOrder(BillingPeriod.MONTHLY, openedAt, 3500, 0));
        when(orders.findSettledPeriodicOrders(
            any(),
            any(),
            anyCollection()
        )).thenReturn(history);

        assertThat(valuation
            .valueOf(entitlement, Instant.parse("2026-03-01T00:00:00Z"))
            .amountMinor()
        ).isZero();
    }

    @Test
    void valuesASubscriptionWithNoSettledHistoryAtNothing() {
        SubscriptionEntitlement entitlement =
            periodicSubscription(Instant.parse("2026-02-01T00:00:00Z"));
        when(orders.findSettledPeriodicOrders(any(), any(), anyCollection()))
            .thenReturn(List.of());

        assertThat(valuation
            .valueOf(entitlement, Instant.parse("2026-01-17T00:00:00Z"))
            .amountMinor()
        ).isZero();
    }

    private SubscriptionEntitlement trafficPackage(
        long quota,
        long uploaded,
        long downloaded
    ) {
        SubscriptionEntitlement entitlement =
            mock(SubscriptionEntitlement.class);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(USER_ID);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getExpiresAt()).thenReturn(null);
        when(entitlement.getTransferLimitBytes()).thenReturn(quota);
        when(entitlement.getUploadedBytes()).thenReturn(uploaded);
        when(entitlement.getDownloadedBytes()).thenReturn(downloaded);
        return entitlement;
    }

    private SubscriptionEntitlement periodicSubscription(Instant expiresAt) {
        SubscriptionEntitlement entitlement =
            mock(SubscriptionEntitlement.class);
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(USER_ID);
        when(entitlement.getUser()).thenReturn(user);
        when(entitlement.getExpiresAt()).thenReturn(expiresAt);
        return entitlement;
    }

    private ServiceOrder settledOrder(
        BillingPeriod period,
        Instant createdAt,
        long totalAmount,
        long balanceAmount
    ) {
        ServicePlan plan = mock(ServicePlan.class);
        when(plan.getName()).thenReturn("Pro");
        UserAccount user = mock(UserAccount.class);
        return ServiceOrder.create(
            "SX0000",
            user,
            plan,
            period,
            OrderType.NEW_PURCHASE,
            "CNY",
            new OrderPricing.Breakdown(
                totalAmount + balanceAmount,
                0,
                0,
                0,
                balanceAmount,
                totalAmount
            ),
            null,
            "[]",
            createdAt
        );
    }
}
