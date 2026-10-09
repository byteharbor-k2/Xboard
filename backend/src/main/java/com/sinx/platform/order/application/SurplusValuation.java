package com.sinx.platform.order.application;

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
 * A traffic package is valued by traffic not consumed, including consumption
 * before a manual counter reset. A periodic subscription values the current
 * reset cycle by the lesser of remaining time and foldable traffic, then adds
 * future funded coverage in full. Each segment retains its original funded
 * duration even when an administrator clips the entitlement expiry.
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
        long used = entitlement.cycleUsedBytes();
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
     * A periodic subscription is valued from its funded coverage segments.
     * Current-cycle value is limited by both elapsed time and traffic consumed;
     * later cycles retain their funded share. Each renewal keeps its own funded
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
            Instant fundedEnd = order.getCoverageEnd();
            if (start == null || fundedEnd == null) {
                Instant paidAt = paidAtOrCreatedAt(order);
                start = order.getOrderType() == com.sinx.platform.order.domain.OrderType.RENEWAL
                        && previousEnd != null && previousEnd.isAfter(paidAt)
                    ? previousEnd : paidAt;
                fundedEnd = start.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
                if (entitlementStart != null && start.isBefore(entitlementStart)) {
                    previousEnd = fundedEnd;
                    continue;
                }
            }
            previousEnd = fundedEnd;
            Instant availableEnd = fundedEnd;
            if (entitlementEnd != null && entitlementEnd.isBefore(availableEnd)) {
                availableEnd = entitlementEnd;
            }
            if (!availableEnd.isAfter(now) || !fundedEnd.isAfter(start)) {
                continue;
            }
            BigInteger funded = order.settledValueBigInteger().max(BigInteger.ZERO);
            if (funded.signum() == 0) {
                continue;
            }
            BigInteger segmentNanos = durationNanos(start, fundedEnd);
            if (segmentNanos.signum() <= 0) continue;

            Instant cycleStart = entitlement.getTrafficCycleStart();
            Instant cycleEnd = entitlement.getTrafficCycleEnd();
            if (cycleStart == null) cycleStart = start;
            if (cycleEnd == null) cycleEnd = availableEnd;
            Instant fundedCycleStart = later(start, cycleStart);
            Instant fundedCycleEnd = earlier(availableEnd, cycleEnd);
            long quota = entitlement.getTransferLimitBytes();
            BigInteger quotaDivisor = BigInteger.valueOf(Math.max(quota, 1));
            BigInteger weightedRemainingNanos = BigInteger.ZERO;
            if (fundedCycleEnd.isAfter(fundedCycleStart)
                    && availableEnd.isAfter(now)) {
                Instant remainingStart = later(now, fundedCycleStart);
                BigInteger cycleSliceNanos = durationNanos(fundedCycleStart, fundedCycleEnd);
                BigInteger remainingNanos = durationNanos(remainingStart, fundedCycleEnd);
                long remainingTraffic = quota <= 0 ? 0
                    : Math.max(0, quota - Math.min(quota, entitlement.cycleUsedBytes()));
                if (cycleSliceNanos.signum() > 0 && remainingNanos.signum() > 0
                        && remainingTraffic > 0) {
                    BigInteger timeWeighted = remainingNanos
                        .multiply(BigInteger.valueOf(quota));
                    BigInteger trafficWeighted = cycleSliceNanos
                        .multiply(BigInteger.valueOf(remainingTraffic));
                    weightedRemainingNanos = weightedRemainingNanos
                        .add(timeWeighted.min(trafficWeighted));
                }
            }

            // Annual or multi-year orders fund later reset cycles too. Preserve
            // their future share in full; current-cycle traffic use must not
            // consume value assigned to a future cycle.
            Instant futureStart = later(now, later(start, cycleEnd));
            if (availableEnd.isAfter(futureStart)) {
                weightedRemainingNanos = weightedRemainingNanos.add(
                    durationNanos(futureStart, availableEnd).multiply(quotaDivisor));
            }
            BigInteger value = funded.multiply(weightedRemainingNanos)
                .divide(segmentNanos.multiply(quotaDivisor));
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

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static Instant earlier(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static int monthsOf(BillingPeriod period) {
        Integer months = period.getMonthCount();
        return months == null ? 0 : months;
    }

}
