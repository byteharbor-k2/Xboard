package com.sinx.platform.order.graphql;

import java.util.UUID;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.application.OrderQuoteView;
import com.sinx.platform.order.domain.OrderType;

/**
 * Minor amounts leave as strings: they are 64-bit and JSON numbers are not.
 */
public record OrderQuotePayload(
    UUID planId,
    String planName,
    BillingPeriod period,
    OrderType orderType,
    String currency,
    String originalAmount,
    String discountAmount,
    String surplusAmount,
    String surplusCredit,
    String balanceAmount,
    String totalAmount,
    String couponCode,
    String couponName,
    String accountBalanceMinor
) {

    public static OrderQuotePayload from(OrderQuoteView view) {
        return new OrderQuotePayload(
            view.planId(),
            view.planName(),
            view.period(),
            view.orderType(),
            view.currency(),
            String.valueOf(view.breakdown().originalAmount()),
            String.valueOf(view.breakdown().discountAmount()),
            String.valueOf(view.breakdown().surplusAmount()),
            String.valueOf(view.breakdown().surplusCredit()),
            String.valueOf(view.breakdown().balanceAmount()),
            String.valueOf(view.breakdown().totalAmount()),
            view.couponCode(),
            view.couponName(),
            String.valueOf(view.accountBalanceMinor())
        );
    }
}
