package com.sinx.platform.order.application;

import java.util.UUID;

import com.sinx.platform.order.domain.CommissionLog;

public record CommissionLogView(
    UUID id,
    String tradeNo,
    String orderAmountMinor,
    String commissionBaseMinor,
    String amountMinor,
    int level,
    String createdAt
) {
    public static CommissionLogView from(CommissionLog log) {
        return new CommissionLogView(
            log.getId(),
            log.getTradeNo(),
            Long.toString(log.getOrderAmountMinor()),
            Long.toString(log.getCommissionBaseMinor()),
            Long.toString(log.getAmountMinor()),
            log.getLevel(),
            log.getCreatedAt().toString()
        );
    }
}
