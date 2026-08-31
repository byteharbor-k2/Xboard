package com.sinx.platform.order.graphql;

import java.util.UUID;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;

public record ServiceOrderPayload(
    UUID id,
    String tradeNo,
    UUID planId,
    String planName,
    BillingPeriod period,
    OrderType orderType,
    OrderStatus status,
    String currency,
    String originalAmount,
    String discountAmount,
    String surplusAmount,
    String surplusCredit,
    String balanceAmount,
    String totalAmount,
    String createdAt,
    String paidAt
) {

    public static ServiceOrderPayload from(ServiceOrder order) {
        return new ServiceOrderPayload(
            order.getId(),
            order.getTradeNo(),
            order.getPlan().getId(),
            order.getPlanName(),
            order.getPeriod(),
            order.getOrderType(),
            order.getStatus(),
            order.getCurrency(),
            String.valueOf(order.getOriginalAmount()),
            String.valueOf(order.getDiscountAmount()),
            String.valueOf(order.getSurplusAmount()),
            String.valueOf(order.getSurplusCredit()),
            String.valueOf(order.getBalanceAmount()),
            String.valueOf(order.getTotalAmount()),
            order.getCreatedAt().toString(),
            order.getPaidAt() == null ? null : order.getPaidAt().toString()
        );
    }
}
