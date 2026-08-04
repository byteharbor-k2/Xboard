package com.sinx.platform.catalog.domain;

public enum BillingPeriod {
    MONTHLY(30, 1),
    QUARTERLY(90, 3),
    HALF_YEARLY(180, 6),
    YEARLY(365, 12),
    TWO_YEARLY(730, 24),
    THREE_YEARLY(1095, 36),
    ONETIME(null, null),
    RESET_TRAFFIC(null, null);

    private final Integer durationDays;
    private final Integer monthCount;

    BillingPeriod(Integer durationDays, Integer monthCount) {
        this.durationDays = durationDays;
        this.monthCount = monthCount;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public Integer getMonthCount() {
        return monthCount;
    }
}
