package com.sinx.platform.balance.application;

import com.sinx.platform.balance.domain.BalanceLog;

/** A viewer-safe representation of one signed balance movement. */
public record BalanceLogView(
    String id,
    String type,
    String amountMinor,
    String balanceAfterMinor,
    String currency,
    String tradeNo,
    String note,
    String createdAt,
    boolean canViewOrder
) {
    public static BalanceLogView from(BalanceLog log, boolean canViewOrder) {
        return new BalanceLogView(
            log.getId().toString(),
            log.getType().name(),
            Long.toString(log.getAmountMinor()),
            Long.toString(log.getBalanceAfterMinor()),
            log.getCurrency(),
            log.getTradeNo(),
            log.getNote(),
            log.getCreatedAt().toString(),
            canViewOrder
        );
    }
}
