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
        // Nothing left to pay means there is nothing to wait for.
        order.status = breakdown.totalAmount() == 0
            ? OrderStatus.DISCOUNTED
            : OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    public void cancel(Instant now) {
        status = OrderStatus.CANCELLED;
        canceledAt = now;
        updatedAt = now;
    }

    public boolean isOpen() {
        return status == OrderStatus.PENDING || status == OrderStatus.PROCESSING;
    }

    /**
     * True while nothing has been delivered for this order.
     *
     * A fully discounted order is not awaiting payment, but until provisioning
     * exists it has still taken the customer's balance and coupon without
     * giving anything back, so it has to remain undoable.
     */
    public boolean isRevocable() {
        return status != OrderStatus.COMPLETED
            && status != OrderStatus.CANCELLED;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}
