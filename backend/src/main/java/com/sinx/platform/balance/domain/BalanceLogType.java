package com.sinx.platform.balance.domain;

/** The only cash movements recorded against an ordinary site balance. */
public enum BalanceLogType {
    OPENING_BALANCE,
    ORDER_PAYMENT,
    ORDER_REFUND,
    SURPLUS_CREDIT,
    COMMISSION_CREDIT,
    ADMIN_ADJUSTMENT
}
