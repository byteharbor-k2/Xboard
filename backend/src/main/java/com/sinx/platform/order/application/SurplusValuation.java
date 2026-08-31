package com.sinx.platform.order.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

/**
 * Values what a customer has already paid for but not yet consumed, so an
 * upgrade only charges the difference.
 *
 * Follows the original panel's two cases. A traffic package is valued by the
 * traffic left in it; a periodic subscription is valued by the share of its
 * paid-through window that has not elapsed. Both derive the paid-through point
 * from the settled order history rather than the current expiry, so a manually
 * adjusted expiry cannot inflate a refund.
 *
 * All arithmetic goes through BigInteger/BigDecimal: byte counts multiplied by
 * amounts overflow a long for large packages, and a double ratio would drift.
 */
@Service
@Transactional(readOnly = true)
public class SurplusValuation {

    private static final Set<BillingPeriod> NOT_PART_OF_A_CYCLE = Set.of(
        BillingPeriod.RESET_TRAFFIC,
        BillingPeriod.ONETIME
    );

    private final ServiceOrderRepository orders;

    public SurplusValuation(ServiceOrderRepository orders) {
        this.orders = orders;
    }

    public record Surplus(long amountMinor, List<UUID> consumedOrderIds) {

        static final Surplus NONE = new Surplus(0, List.of());
    }

    public Surplus valueOf(SubscriptionEntitlement entitlement, Instant now) {
        if (entitlement == null) {
            return Surplus.NONE;
        }
        UUID userId = entitlement.getUser().getId();
        return entitlement.getExpiresAt() == null
            ? valueTrafficPackage(entitlement, userId)
            : valuePeriodicSubscription(entitlement, userId, now);
    }

    /**
     * A package without an expiry is worth the traffic still in it, priced at
     * what the customer actually paid per byte.
     */
    private Surplus valueTrafficPackage(
        SubscriptionEntitlement entitlement,
        UUID userId
    ) {
        Optional<ServiceOrder> lastPackage = orders.findLatestSettledForPeriod(
            userId,
            OrderStatus.COMPLETED,
            BillingPeriod.ONETIME
        );
        if (lastPackage.isEmpty()) {
            return Surplus.NONE;
        }
        long quota = entitlement.getTransferLimitBytes();
        long paid = lastPackage.get().getTotalAmount()
            + lastPackage.get().getBalanceAmount();
        if (quota <= 0 || paid <= 0) {
            return Surplus.NONE;
        }
        long used = entitlement.getUploadedBytes()
            + entitlement.getDownloadedBytes();
        long remaining = Math.max(0, quota - used);
        if (remaining == 0) {
            return Surplus.NONE;
        }

        long amount = BigInteger.valueOf(paid)
            .multiply(BigInteger.valueOf(remaining))
            .divide(BigInteger.valueOf(quota))
            .longValueExact();

        return new Surplus(
            amount,
            orders.findSettledOrderIdsExcludingPeriod(
                userId,
                OrderStatus.COMPLETED,
                BillingPeriod.RESET_TRAFFIC
            )
        );
    }

    /**
     * A periodic subscription is worth the unelapsed share of everything paid
     * into it. The window runs from the first settled order to that order plus
     * the total months bought since.
     */
    private Surplus valuePeriodicSubscription(
        SubscriptionEntitlement entitlement,
        UUID userId,
        Instant now
    ) {
        List<ServiceOrder> history = orders.findSettledPeriodicOrders(
            userId,
            OrderStatus.COMPLETED,
            NOT_PART_OF_A_CYCLE
        );
        if (history.isEmpty()) {
            return Surplus.NONE;
        }

        long paidIn = history.stream()
            .mapToLong(ServiceOrder::settledValue)
            .sum();
        int monthsBought = history.stream()
            .mapToInt(order -> monthsOf(order.getPeriod()))
            .sum();
        if (paidIn <= 0 || monthsBought <= 0) {
            return Surplus.NONE;
        }

        Instant openedAt = history.get(0).getCreatedAt();
        Instant paidThrough = openedAt.atZone(ZoneOffset.UTC)
            .plusMonths(monthsBought)
            .toInstant();

        long windowSeconds = paidThrough.getEpochSecond()
            - openedAt.getEpochSecond();
        long remainingSeconds = Math.max(
            0,
            paidThrough.getEpochSecond() - now.getEpochSecond()
        );
        if (windowSeconds <= 0 || remainingSeconds == 0) {
            return new Surplus(0, ids(history));
        }

        long amount = BigDecimal.valueOf(paidIn)
            .multiply(BigDecimal.valueOf(remainingSeconds))
            .divide(BigDecimal.valueOf(windowSeconds), 0, RoundingMode.DOWN)
            .longValueExact();

        return new Surplus(Math.max(0, amount), ids(history));
    }

    private static int monthsOf(BillingPeriod period) {
        Integer months = period.getMonthCount();
        return months == null ? 0 : months;
    }

    private static List<UUID> ids(List<ServiceOrder> history) {
        return history.stream().map(ServiceOrder::getId).toList();
    }
}
