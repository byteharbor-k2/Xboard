package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Where the monthly traffic cycle of a {@code MONTHLY_FROM_ACTIVATION}
 * entitlement sits.
 *
 * The chain is anchored at the activation instant and every boundary is a
 * calendar month along it: a subscription opened on the 15th resets on the
 * 15th of every following month. Cycle arithmetic runs in the billing zone,
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

    private static Instant boundaryAfter(Instant instant, ZoneId zone) {
        return ZonedDateTime.ofInstant(instant, zone)
            .plusMonths(1)
            .toInstant();
    }
}
