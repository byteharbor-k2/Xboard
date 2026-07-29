package com.sinx.platform.catalog.application;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlanPrice;

public record PlanPriceView(
    BillingPeriod period,
    String amountMinor,
    String currency,
    Integer durationDays,
    Integer monthCount
) {
    static PlanPriceView from(ServicePlanPrice price) {
        BillingPeriod period = price.getBillingPeriod();
        return new PlanPriceView(
            period,
            Long.toString(price.getAmountMinor()),
            price.getCurrency(),
            period.getDurationDays(),
            period.getMonthCount()
        );
    }
}
