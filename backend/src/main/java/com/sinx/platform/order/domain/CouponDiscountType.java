package com.sinx.platform.order.domain;

public enum CouponDiscountType {
    /** A flat amount in minor units. */
    FIXED_AMOUNT,
    /** A whole percentage of the order's running total. */
    PERCENTAGE
}
