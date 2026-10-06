package com.sinx.platform.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;

class TrafficResetPolicyScheduleTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void mapsAllCalendarAndActivationPoliciesToTheirFirstFutureBoundary() {
        Instant activation = local("2026-03-31T18:42:17");

        assertThat(localTime(MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.FIRST_DAY_OF_MONTH, activation
        ))).isEqualTo(LocalDateTime.parse("2026-04-01T00:00:00"));
        assertThat(localTime(MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, activation
        ))).isEqualTo(LocalDateTime.parse("2026-04-30T18:42:17"));
        assertThat(localTime(MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.FIRST_DAY_OF_YEAR, activation
        ))).isEqualTo(LocalDateTime.parse("2027-01-01T00:00:00"));
        assertThat(localTime(MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.YEARLY_FROM_ACTIVATION, activation
        ))).isEqualTo(LocalDateTime.parse("2027-03-31T18:42:17"));
        assertThat(MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.NEVER, activation
        )).isNull();
    }

    @Test
    void monthlyCycleRetainsExistingEndOfMonthDrift() {
        Instant januaryEnd = local("2025-01-31T09:15:00");
        Instant february = MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, januaryEnd
        );

        assertThat(localTime(february))
            .isEqualTo(LocalDateTime.parse("2025-02-28T09:15:00"));
        assertThat(localTime(MonthlyResetSchedule.followingBoundary(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION,
            february,
            february
        ))).isEqualTo(LocalDateTime.parse("2025-03-28T09:15:00"));
    }

    @Test
    void yearlyActivationCycleClampsLeapDayConsistentlyAndCatchesUpOnce() {
        Instant leapDay = local("2024-02-29T07:00:00");
        Instant next = MonthlyResetSchedule.initialBoundary(
            TrafficResetPolicy.YEARLY_FROM_ACTIVATION, leapDay
        );
        assertThat(localTime(next))
            .isEqualTo(LocalDateTime.parse("2025-02-28T07:00:00"));

        Instant afterSeveralYears = local("2026-06-01T00:00:00");
        Instant following = MonthlyResetSchedule.followingBoundary(
            TrafficResetPolicy.YEARLY_FROM_ACTIVATION, next, afterSeveralYears
        );
        assertThat(localTime(following))
            .isEqualTo(LocalDateTime.parse("2027-02-28T07:00:00"));
    }

    @Test
    void reenabledActivationPolicyUsesTheNextBoundaryNotAHistoricalOne() {
        Instant activation = local("2025-08-03T11:00:00");
        Instant now = local("2026-10-06T01:00:00");

        assertThat(localTime(MonthlyResetSchedule.nextBoundary(
            TrafficResetPolicy.MONTHLY_FROM_ACTIVATION, now, activation
        ))).isEqualTo(LocalDateTime.parse("2026-11-03T11:00:00"));
        assertThat(localTime(MonthlyResetSchedule.nextBoundary(
            TrafficResetPolicy.FIRST_DAY_OF_MONTH, now, activation
        ))).isEqualTo(LocalDateTime.parse("2026-11-01T00:00:00"));
    }

    private Instant local(String value) {
        return LocalDateTime.parse(value).atZone(SHANGHAI).toInstant();
    }

    private LocalDateTime localTime(Instant value) {
        return value.atZone(SHANGHAI).toLocalDateTime();
    }
}
