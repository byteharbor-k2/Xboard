package com.sinx.platform.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The month arithmetic behind the monthly traffic cycle.
 *
 * Boundaries are calendar months in the billing zone, Asia/Shanghai, from the
 * anchor - the activation instant of the entitlement, or, once a reset has
 * run, the boundary it satisfied. A statement of the rule the walk of an
 * overdue chain relies on, kept under plain assertions so a zone or an
 * off-by-one month cannot slip silently into everyone's renewal day.
 */
class MonthlyResetScheduleTest {

    @Test
    void anEntitlementActivatedMidMonthResetsOnItsMonthlyAnniversary() {
        Instant activation = Instant.parse("2026-09-15T06:00:00Z");

        assertThat(MonthlyResetSchedule.initialBoundary(activation))
            .isEqualTo(Instant.parse("2026-10-15T06:00:00Z"));
        assertThat(MonthlyResetSchedule.followingBoundary(
                Instant.parse("2026-10-15T06:00:00Z"),
                Instant.parse("2026-10-20T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-11-15T06:00:00Z"));

        // A boundary two months passed walks both at once - the first run
        // after a deployment that never had a cycle catches up in one go.
        assertThat(MonthlyResetSchedule.followingBoundary(
                Instant.parse("2026-09-15T06:00:00Z"),
                Instant.parse("2026-11-16T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-12-15T06:00:00Z"));
    }

    @Test
    void aBoundaryNotYetReachedStaysUntouched() {
        Instant activation = Instant.parse("2026-09-15T06:00:00Z");
        Instant firstBoundary = MonthlyResetSchedule.initialBoundary(activation);

        assertThat(MonthlyResetSchedule.followingBoundary(
                firstBoundary,
                Instant.parse("2026-10-10T00:00:00Z")))
            .isEqualTo(firstBoundary);
    }

    @Test
    void aChainAnchoredAtTheMonthEndClampsOntoTheShorterMonthsEnd() {
        Instant activation = Instant.parse("2026-01-31T10:00:00Z");

        // January 31st has no February 31st: the boundary lands on the
        // 28th, and - because ZonedDateTime keeps the day it walked to -
        // stays on the 28th from then on rather than jumping back.
        Instant february = MonthlyResetSchedule.followingBoundary(
            activation, Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(february).isEqualTo(Instant.parse("2026-02-28T10:00:00Z"));
        assertThat(MonthlyResetSchedule.followingBoundary(
                february,
                Instant.parse("2026-03-01T00:00:00Z")))
            .isEqualTo(Instant.parse("2026-03-28T10:00:00Z"));
    }

    @Test
    void aBoundaryHoursInsideTheBillingZoneDayIsReadThroughThatZone() {
        // 2026-10-01 02:00 Asia/Shanghai is 2026-09-30 18:00 UTC; the zone a
        // month walk reads through decides which civil day a customer calls
        // it. Anchored on the first, the boundary is read as the 1st - in
        // Shanghai, not the 30th of September in UTC.
        Instant activation = Instant.parse("2026-09-01T02:00:00Z");

        assertThat(MonthlyResetSchedule.initialBoundary(activation))
            .isEqualTo(Instant.parse("2026-10-01T02:00:00Z"));
    }

    @Test
    void aManualResetReanchorsTheCycleAtTheResetInstant() {
        Instant reset = Instant.parse("2026-10-01T08:00:00Z");

        assertThat(MonthlyResetSchedule.followingBoundary(reset, reset))
            .isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
    }
}
