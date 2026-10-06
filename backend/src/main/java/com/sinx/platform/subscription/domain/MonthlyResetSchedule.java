package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;

/**
 * Calendar and activation boundaries for an entitlement's traffic reset cycle.
 *
 * Activation-based cycles are anchored at the activation instant: a monthly
 * subscription opened on the 15th resets on the 15th of each following month.
 * Cycle arithmetic runs in the billing zone,
 * Asia/Shanghai - the original deployment's server clock, the same pinned
 * zone the reminder mail and the node billing share - because an anchor near
 * midnight must not move its day when read from another zone.
 *
 * A boundary that celebrates a day the shorter months do not have clamps to
 * the month's end and keeps the walked day from then on: one anchored on
 * January 31st falls onto February 28th (29th in a leap year), and from there
 * the following boundaries stay on the 28th. Keeping this plain, as the
 * coverage arithmetic in order fulfilment does, beats a shifting rule that
 * would quietly move the anniversary day of every customer.
 */
public final class MonthlyResetSchedule {

    /** The zone the deployment bills in; pinned, as the mail job's is. */
    public static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");

    private MonthlyResetSchedule() {
    }

    /**
     * The first boundary of an entitlement activated at {@code activatedAt}:
     * one calendar month after the activation, the first time the counters
     * come up for a scheduled reset.
     */
    public static Instant initialBoundary(Instant activatedAt) {
        return boundaryAfter(activatedAt, BILLING_ZONE);
    }

    /** First future boundary for a plan reset method, without a retroactive reset. */
    public static Instant initialBoundary(
        TrafficResetPolicy policy,
        Instant activatedAt
    ) {
        ZonedDateTime activation = activatedAt.atZone(BILLING_ZONE);
        return switch (policy) {
            case MONTHLY_FROM_ACTIVATION -> activation.plusMonths(1).toInstant();
            case YEARLY_FROM_ACTIVATION -> activation.plusYears(1).toInstant();
            case FIRST_DAY_OF_MONTH -> activation.toLocalDate()
                .withDayOfMonth(1).plusMonths(1).atStartOfDay(BILLING_ZONE).toInstant();
            case FIRST_DAY_OF_YEAR -> LocalDate.of(activation.getYear() + 1, 1, 1)
                .atStartOfDay(BILLING_ZONE).toInstant();
            case NEVER -> null;
        };
    }

    /** The next boundary strictly after now when a policy is assigned or changed. */
    public static Instant nextBoundary(
        TrafficResetPolicy policy,
        Instant now,
        Instant activation
    ) {
        ZonedDateTime current = now.atZone(BILLING_ZONE);
        return switch (policy) {
            case NEVER -> null;
            case FIRST_DAY_OF_MONTH -> current.toLocalDate().withDayOfMonth(1)
                .plusMonths(1).atStartOfDay(BILLING_ZONE).toInstant();
            case FIRST_DAY_OF_YEAR -> LocalDate.of(current.getYear() + 1, 1, 1)
                .atStartOfDay(BILLING_ZONE).toInstant();
            case MONTHLY_FROM_ACTIVATION -> nextActivationBoundary(
                activation, now, false
            );
            case YEARLY_FROM_ACTIVATION -> nextActivationBoundary(
                activation, now, true
            );
        };
    }

    private static Instant nextActivationBoundary(
        Instant activation,
        Instant now,
        boolean yearly
    ) {
        ZonedDateTime candidate = activation.atZone(BILLING_ZONE);
        while (!candidate.toInstant().isAfter(now)) {
            candidate = yearly ? candidate.plusYears(1) : candidate.plusMonths(1);
        }
        return candidate.toInstant();
    }

    /**
     * The boundary following a passed one, walked far enough that it lies
     * strictly after {@code afterInstant}.
     *
     * A fresh entitlement whose cycle is seeded from its activation instant
     * carries a boundary already in the past; the walk brings it onto the
     * first due boundary of the chain and starts the cycle there. One long
     * walk, one catch-up reset - afterwards the chain keeps pace.
     */
    public static Instant followingBoundary(Instant boundary, Instant afterInstant) {
        ZonedDateTime candidate = ZonedDateTime.ofInstant(boundary, BILLING_ZONE);
        while (!candidate.toInstant().isAfter(afterInstant)) {
            candidate = candidate.plusMonths(1);
        }
        return candidate.toInstant();
    }

    /** Advance a due cycle once to its first future calendar boundary. */
    public static Instant followingBoundary(
        TrafficResetPolicy policy,
        Instant previous,
        Instant after
    ) {
        if (policy == TrafficResetPolicy.MONTHLY_FROM_ACTIVATION) {
            return followingBoundary(previous, after);
        }
        if (policy == TrafficResetPolicy.YEARLY_FROM_ACTIVATION) {
            ZonedDateTime candidate = previous.atZone(BILLING_ZONE);
            while (!candidate.toInstant().isAfter(after)) {
                candidate = candidate.plusYears(1);
            }
            return candidate.toInstant();
        }
        if (policy == TrafficResetPolicy.FIRST_DAY_OF_MONTH) {
            ZonedDateTime candidate = previous.atZone(BILLING_ZONE)
                .withDayOfMonth(1);
            while (!candidate.toInstant().isAfter(after)) {
                candidate = candidate.plusMonths(1);
            }
            return candidate.toInstant();
        }
        if (policy == TrafficResetPolicy.FIRST_DAY_OF_YEAR) {
            ZonedDateTime candidate = previous.atZone(BILLING_ZONE)
                .withDayOfYear(1);
            while (!candidate.toInstant().isAfter(after)) {
                candidate = candidate.plusYears(1);
            }
            return candidate.toInstant();
        }
        return null;
    }

    private static Instant boundaryAfter(Instant instant, ZoneId zone) {
        return ZonedDateTime.ofInstant(instant, zone)
            .plusMonths(1)
            .toInstant();
    }
}
