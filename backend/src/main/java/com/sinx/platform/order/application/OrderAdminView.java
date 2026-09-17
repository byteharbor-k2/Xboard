package com.sinx.platform.order.application;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.OrderType;
import com.sinx.platform.order.domain.ServiceOrder;

/**
 * One order as the admin API reports it.
 *
 * Field names and epoch-second timestamps follow the original panel's admin
 * responses, since these endpoints sit on the Xboard-compatible surface under
 * {@code /api/v2/admin}. Amounts are minor units, as everywhere else.
 */
public record OrderAdminView(
    @JsonProperty("trade_no") String tradeNo,
    @JsonProperty("user_id") UUID userId,
    @JsonProperty("email") String email,
    @JsonProperty("plan_id") UUID planId,
    @JsonProperty("plan_name") String planName,
    @JsonProperty("period") BillingPeriod period,
    @JsonProperty("order_type") OrderType orderType,
    @JsonProperty("status") OrderStatus status,
    @JsonProperty("currency") String currency,
    @JsonProperty("original_amount") long originalAmount,
    @JsonProperty("discount_amount") long discountAmount,
    @JsonProperty("surplus_amount") long surplusAmount,
    @JsonProperty("surplus_credit") long surplusCredit,
    @JsonProperty("balance_amount") long balanceAmount,
    @JsonProperty("total_amount") long totalAmount,
    @JsonProperty("callback_no") String callbackNo,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("paid_at") Long paidAt
) {
    static OrderAdminView from(ServiceOrder order) {
        return new OrderAdminView(
            order.getTradeNo(),
            order.getUser().getId(),
            order.getUser().getEmail(),
            order.getPlan().getId(),
            order.getPlanName(),
            order.getPeriod(),
            order.getOrderType(),
            order.getStatus(),
            order.getCurrency(),
            order.getOriginalAmount(),
            order.getDiscountAmount(),
            order.getSurplusAmount(),
            order.getSurplusCredit(),
            order.getBalanceAmount(),
            order.getTotalAmount(),
            order.getCallbackNo(),
            order.getCreatedAt().getEpochSecond(),
            order.getPaidAt() == null ? null : order.getPaidAt().getEpochSecond()
        );
    }
}
