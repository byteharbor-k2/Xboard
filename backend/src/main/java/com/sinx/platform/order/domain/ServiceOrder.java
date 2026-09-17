package com.sinx.platform.order.domain;

import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.identity.domain.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A purchase of a plan for one billing period.
 *
 * Every amount is in minor units. The fields mirror the original panel's order
 * record so the arithmetic stays auditable: {@code originalAmount} is the list
 * price, each deduction is kept separately, and {@code totalAmount} is what is
 * left to pay.
 */
@Entity
@Table(name = "orders")
public class ServiceOrder {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "trade_no", nullable = false, length = 32, updatable = false)
    private String tradeNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ServicePlan plan;

    @Column(name = "plan_name", nullable = false, length = 255)
    private String planName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private BillingPeriod period;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 24)
    private OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderStatus status;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "original_amount", nullable = false)
    private long originalAmount;

    @Column(name = "discount_amount", nullable = false)
    private long discountAmount;

    @Column(name = "surplus_amount", nullable = false)
    private long surplusAmount;

    @Column(name = "surplus_credit", nullable = false)
    private long surplusCredit;

    @Column(name = "balance_amount", nullable = false)
    private long balanceAmount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "coupon_id")
    private UUID couponId;

    @Column(
        name = "surplus_order_ids",
        nullable = false,
        columnDefinition = "text"
    )
    private String surplusOrderIds = "[]";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Payment reference, or {@code manual_operation} for an admin settlement. */
    @Column(name = "callback_no", length = 64)
    private String callbackNo;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected ServiceOrder() {
    }

    public static ServiceOrder create(
        String tradeNo,
        UserAccount user,
        ServicePlan plan,
        BillingPeriod period,
        OrderType orderType,
        String currency,
        OrderPricing.Breakdown breakdown,
        UUID couponId,
        String surplusOrderIds,
        Instant now
    ) {
        ServiceOrder order = new ServiceOrder();
        order.id = UUID.randomUUID();
        order.tradeNo = tradeNo;
        order.user = user;
        order.plan = plan;
        order.planName = plan.getName();
        order.period = period;
        order.orderType = orderType;
        order.currency = currency;
        order.originalAmount = breakdown.originalAmount();
        order.discountAmount = breakdown.discountAmount();
        order.surplusAmount = breakdown.surplusAmount();
        order.surplusCredit = breakdown.surplusCredit();
        order.balanceAmount = breakdown.balanceAmount();
        order.totalAmount = breakdown.totalAmount();
        order.couponId = couponId;
        order.surplusOrderIds = surplusOrderIds == null ? "[]" : surplusOrderIds;
        // Every order starts unpaid, however much of it the discounts covered.
        // A total of zero is not a settled order: nothing is provisioned until
        // a payment - or an admin settling it by hand - moves it on.
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void cancel(Instant now) {
        status = OrderStatus.CANCELLED;
        canceledAt = now;
        updatedAt = now;
    }

    /**
     * Settles the order and hands it to provisioning.
     *
     * Only a pending order can be settled, so a duplicate payment callback
     * cannot provision twice.
     */
    public void markPaid(String callbackNo, Instant now) {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                "Only a pending order can be settled, was " + status
            );
        }
        status = OrderStatus.PROCESSING;
        paidAt = now;
        this.callbackNo = callbackNo;
        updatedAt = now;
    }

    public void complete(Instant now) {
        status = OrderStatus.COMPLETED;
        updatedAt = now;
    }

    /**
     * Records that a later upgrade spent whatever value was left in this order.
     */
    public void markDiscounted(Instant now) {
        status = OrderStatus.DISCOUNTED;
        updatedAt = now;
    }

    public boolean isPending() {
        return status == OrderStatus.PENDING;
    }

    public boolean isProcessing() {
        return status == OrderStatus.PROCESSING;
    }

    /** Value this order contributed, as the original panel's surplus sum does. */
    public long settledValue() {
        return totalAmount + balanceAmount + surplusAmount - surplusCredit;
    }

    public UUID getId() {
        return id;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public UserAccount getUser() {
        return user;
    }

    public ServicePlan getPlan() {
        return plan;
    }

    public String getPlanName() {
        return planName;
    }

    public BillingPeriod getPeriod() {
        return period;
    }

    public OrderType getOrderType() {
        return orderType;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public long getOriginalAmount() {
        return originalAmount;
    }

    public long getDiscountAmount() {
        return discountAmount;
    }

    public long getSurplusAmount() {
        return surplusAmount;
    }

    public long getSurplusCredit() {
        return surplusCredit;
    }

    public long getBalanceAmount() {
        return balanceAmount;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public UUID getCouponId() {
        return couponId;
    }

    /** The orders a later upgrade consumed, as a JSON array of ids. */
    public String getSurplusOrderIds() {
        return surplusOrderIds;
    }

    public String getCallbackNo() {
        return callbackNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
