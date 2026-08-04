package com.sinx.platform.catalog.application;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlanPrice;

public record ManagedPlanPriceView(
    BillingPeriod period,
    long amountMinor,
    String currency
) {
    static ManagedPlanPriceView from(ServicePlanPrice price) {
        return new ManagedPlanPriceView(
            price.getBillingPeriod(),
            price.getAmountMinor(),
            price.getCurrency()
        );
    }
}
