package com.sinx.platform.order.domain;

public enum OrderStatus {
    /** Awaiting payment. */
    PENDING,
    /** Paid, provisioning the entitlement. */
    PROCESSING,
    CANCELLED,
    COMPLETED,
    /** Settled entirely by discounts, so no payment was ever needed. */
    DISCOUNTED
}
