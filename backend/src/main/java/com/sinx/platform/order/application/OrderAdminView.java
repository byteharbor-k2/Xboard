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
    @JsonProperty("paid_at") Long paidAt,
    @JsonProperty("invite_user_id") UUID inviteUserId,
    @JsonProperty("commission_base") long commissionBase,
    @JsonProperty("commission_balance") long commissionBalance,
    @JsonProperty("commission_status") Integer commissionStatus,
    @JsonProperty("actual_commission_balance") long actualCommissionBalance
) {
    /** Keeps existing callers that build pre-commission admin fixtures compatible. */
    public OrderAdminView(
        String tradeNo,
        UUID userId,
        String email,
        UUID planId,
        String planName,
        BillingPeriod period,
        OrderType orderType,
        OrderStatus status,
        String currency,
        long originalAmount,
        long discountAmount,
        long surplusAmount,
        long surplusCredit,
        long balanceAmount,
        long totalAmount,
        String callbackNo,
        long createdAt,
        Long paidAt
    ) {
        this(
            tradeNo, userId, email, planId, planName, period, orderType, status,
            currency, originalAmount, discountAmount, surplusAmount,
            surplusCredit, balanceAmount, totalAmount, callbackNo, createdAt,
            paidAt, null, 0, 0, null, 0
        );
    }

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
            order.getPaidAt() == null ? null : order.getPaidAt().getEpochSecond(),
            order.getInviteUserId(),
            order.getCommissionBase(),
            order.getCommissionBalance(),
            order.getCommissionStatus(),
            order.getActualCommissionBalance()
        );
    }
}
