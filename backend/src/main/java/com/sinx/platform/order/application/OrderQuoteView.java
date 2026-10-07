package com.sinx.platform.order.application;

import java.util.UUID;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderPricing;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.OrderDeductionMode;

/**
 * What a purchase would cost, itemised so the customer can see where each
 * deduction came from rather than just a final number.
 */
public record OrderQuoteView(
    UUID planId,
    String planName,
    BillingPeriod period,
    OrderType orderType,
    String currency,
    OrderPricing.Breakdown breakdown,
    String couponCode,
    String couponName,
    long accountBalanceMinor,
    OrderDeductionMode deductionMode,
    long deferredSurplusCreditMinor,
    boolean minimumOnlinePaymentBlocked,
    String minimumPaymentMessage
) {
    public OrderQuoteView(UUID planId, String planName, BillingPeriod period,
        OrderType orderType, String currency, OrderPricing.Breakdown breakdown,
        String couponCode, String couponName, long accountBalanceMinor) {
        this(planId, planName, period, orderType, currency, breakdown, couponCode,
            couponName, accountBalanceMinor, OrderDeductionMode.STANDARD, 0,
            breakdown.totalAmount() > 0 && breakdown.totalAmount() < 1000,
            breakdown.totalAmount() > 0 && breakdown.totalAmount() < 1000
                ? OrderService.MINIMUM_PAYMENT_MESSAGE : null);
    }
}
