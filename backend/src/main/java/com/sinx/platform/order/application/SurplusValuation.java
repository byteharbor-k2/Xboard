package com.sinx.platform.order.application;

import java.math.BigInteger;
import java.math.BigInteger;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
 * All prorating uses integer nanosecond intervals and BigInteger minor-unit
 * products: large byte counts or high-value segments cannot overflow a long,
 * and a floating-point ratio would drift.
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
     * A package without an expiry is worth the traffic still in it. The latest
     * completed package's settled value is its coupon-adjusted funding plus
     * previously carried surplus, minus surplus returned to the balance. The
     * prior source package is marked DISCOUNTED when this package fulfils, so
     * only the latest package is valued on the next change; the same carried
     * amount is never counted both in the current package and its old source.
     * Coupon-only funding has a zero settled value and therefore creates no
     * refundable package value.
     */
    private Surplus valueTrafficPackage(
        SubscriptionEntitlement entitlement,
        UUID userId
    ) {
        Optional<ServiceOrder> lastPackage = orders.findLatestSettledForPeriodAndUser(
            userId, BillingPeriod.ONETIME, OrderStatus.COMPLETED
        );
        if (lastPackage.isEmpty()) {
            return Surplus.NONE;
        }
        long quota = entitlement.getTransferLimitBytes();
        // The latest package may itself have been funded partly by carried
        // value. Its settled value includes that amount once, while its source
        // order is written off atomically when this package is fulfilled.
        BigInteger funded = lastPackage.get().settledValueBigInteger();
        if (quota <= 0 || funded.signum() <= 0) {
            return Surplus.NONE;
        }
        long used = entitlement.usedBytes();
        long remaining = Math.max(0, quota - used);
        if (remaining == 0) {
            return Surplus.NONE;
        }

        long amount = funded
            .multiply(BigInteger.valueOf(remaining))
            .divide(BigInteger.valueOf(quota))
            .longValueExact();

        return new Surplus(
            amount,
            orders.findSettledOrderIdsForPeriod(
                userId,
                OrderStatus.COMPLETED,
                BillingPeriod.ONETIME
            )
        );
    }

    /**
     * A periodic subscription is valued as the sum of the unconsumed shares of
     * its currently funded coverage segments. Each renewal keeps its own funded
     * amount and actual start/end, so a gap, delayed callback or different
     * renewal price cannot be averaged into an imaginary continuous window.
     * Legacy rows without those snapshots are rebuilt from paid_at and order
     * type, constrained to the current plan and entitlement anchor/expiry.
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
        List<ServiceOrder> currentPlanHistory = history.stream()
            .filter(order -> order.getPlan().getId().equals(entitlement.getPlanId()))
            .sorted(Comparator.comparing(this::paidAtOrCreatedAt))
            .toList();
        if (currentPlanHistory.isEmpty()) {
            return Surplus.NONE;
        }

        BigInteger totalValue = BigInteger.ZERO;
        List<UUID> consumed = new ArrayList<>();
        Instant previousEnd = null;
        Instant entitlementStart = entitlement.getStartsAt();
        Instant entitlementEnd = entitlement.getExpiresAt();
        for (ServiceOrder order : currentPlanHistory) {
            int months = monthsOf(order.getPeriod());
            if (months <= 0) {
                continue;
            }
            Instant start = order.getCoverageStart();
            Instant end = order.getCoverageEnd();
            if (start == null || end == null) {
                Instant paidAt = paidAtOrCreatedAt(order);
                start = order.getOrderType() == com.sinx.platform.order.domain.OrderType.RENEWAL
                        && previousEnd != null && previousEnd.isAfter(paidAt)
                    ? previousEnd : paidAt;
                end = start.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
                if (entitlementStart != null && start.isBefore(entitlementStart)) {
                    previousEnd = end;
                    continue;
                }
            }
            previousEnd = end;
            if (entitlementEnd != null && entitlementEnd.isBefore(end)) {
                end = entitlementEnd;
            }
            if (!end.isAfter(now) || !end.isAfter(start)) {
                continue;
            }
            BigInteger funded = order.settledValueBigInteger().max(BigInteger.ZERO);
            if (funded.signum() == 0) {
                continue;
            }
            Instant remainingStart = now.isAfter(start) ? now : start;
            BigInteger segmentNanos = durationNanos(start, end);
            BigInteger remainingNanos = durationNanos(remainingStart, end);
            if (segmentNanos.signum() <= 0 || remainingNanos.signum() <= 0) {
                continue;
            }
            BigInteger value = funded.multiply(remainingNanos).divide(segmentNanos);
            if (value.signum() > 0) {
                totalValue = totalValue.add(value);
                consumed.add(order.getId());
            }
        }
        if (totalValue.signum() <= 0) {
            return Surplus.NONE;
        }
        return new Surplus(totalValue.longValueExact(), List.copyOf(consumed));
    }

    private Instant paidAtOrCreatedAt(ServiceOrder order) {
        return order.getPaidAt() == null ? order.getCreatedAt() : order.getPaidAt();
    }

    private static BigInteger durationNanos(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return BigInteger.valueOf(duration.getSeconds())
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .add(BigInteger.valueOf(duration.getNano()));
    }

    private static int monthsOf(BillingPeriod period) {
        Integer months = period.getMonthCount();
        return months == null ? 0 : months;
    }

}
