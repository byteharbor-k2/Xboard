package com.sinx.platform.order.domain;

public enum OrderStatus {
    /** Awaiting payment. */
    PENDING,
    /** Paid, provisioning the entitlement. */
    PROCESSING,
    CANCELLED,
    COMPLETED,
    /**
     * An earlier order whose remaining value a later upgrade consumed.
     *
     * The original panel marks these so the surplus calculation cannot cash the
     * same order in twice; it is a record of what was spent, not a state a newly
     * placed order can ever be in.
     */
    DISCOUNTED
}
