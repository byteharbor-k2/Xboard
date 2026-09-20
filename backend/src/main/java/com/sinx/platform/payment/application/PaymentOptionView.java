package com.sinx.platform.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.sinx.platform.payment.domain.PaymentMethod;

/**
 * One way to pay, priced for a particular order.
 *
 * The handling fee is worked out here rather than in the browser: it is money,
 * and the number the customer is shown has to be the number the order is
 * checked out at.
 *
 * The fee's own parts travel with the quote as well. The customer should be
 * able to see how the surcharge is made up - a percentage, a fixed amount, or
 * both - and that a zero quote means the method charges nothing, not that the
 * order itself is free.
 */
public record PaymentOptionView(
    UUID id,
    String name,
    String icon,
    /** What this method adds on top of the order's total. */
    String handlingFee,
    /** The order's total plus that fee: what will actually be charged. */
    String payableAmount,
    String currency,
    /** The fixed part of the fee, in the currency's minor units. */
    String handlingFeeFixed,
    /** The percentage part of the fee, where 2.5 means 2.5 percent. */
    BigDecimal handlingFeePercent
) {
    static PaymentOptionView of(
        PaymentMethod method,
        long orderTotal,
        String currency
    ) {
        long fee = method.handlingFeeFor(orderTotal);
        return new PaymentOptionView(
            method.getId(),
            method.getName(),
            method.getIcon(),
            String.valueOf(fee),
            String.valueOf(orderTotal + fee),
            currency,
            method.getHandlingFeeFixed() == null
                ? null
                : String.valueOf(method.getHandlingFeeFixed()),
            method.getHandlingFeePercent()
        );
    }
}
