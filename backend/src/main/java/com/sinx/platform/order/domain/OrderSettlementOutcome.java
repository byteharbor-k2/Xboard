package com.sinx.platform.order.domain;

/** The terminal disposition of cash captured for an order. */
public enum OrderSettlementOutcome {
    PENDING,
    SERVICE_FULFILLED,
    BALANCE_RETURNED
}
