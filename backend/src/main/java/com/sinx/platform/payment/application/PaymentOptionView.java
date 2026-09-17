package com.sinx.platform.payment.application;

import java.util.UUID;

import com.sinx.platform.payment.domain.PaymentMethod;

/**
 * One way to pay, priced for a particular order.
 *
 * The handling fee is worked out here rather than in the browser: it is money,
 * and the number the customer is shown has to be the number the order is
 * checked out at.
 */
public record PaymentOptionView(
    UUID id,
    String name,
    String icon,
    /** What this method adds on top of the order's own total. */
    String handlingFee,
    /** The order's total plus that fee: what will actually be charged. */
    String payableAmount,
    String currency
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
            currency
        );
    }
}
