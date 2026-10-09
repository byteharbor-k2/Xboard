package com.sinx.platform.order.domain;

import java.time.Instant;
import java.math.BigInteger;
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

    /** Account whose cash funded the order; assignment changes service ownership, not past postings. */
    @Column(name = "balance_payer_user_id")
    private UUID balancePayerUserId;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "deduction_mode", nullable = false, length = 24)
    private OrderDeductionMode deductionMode = OrderDeductionMode.STANDARD;

    @Column(name = "deferred_surplus_credit", nullable = false)
    private long deferredSurplusCreditMinor;

    @Column(name = "reset_cycle_end")
    private Instant resetCycleEnd;

    @Column(name = "reset_cycle_start")
    private Instant resetCycleStart;

    @Column(name = "reset_cycle_id")
    private UUID resetCycleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_outcome", nullable = false, length = 24)
    private OrderSettlementOutcome settlementOutcome = OrderSettlementOutcome.PENDING;

    @Column(name = "returned_balance_minor", nullable = false)
    private long returnedBalanceMinor;

    @Column(name = "coverage_start")
    private Instant coverageStart;

    @Column(name = "coverage_end")
    private Instant coverageEnd;

    @Column(name = "balance_amount", nullable = false)
    private long balanceAmount;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** Direct inviter and commission calculation frozen at checkout. */
    @Column(name = "invite_user_id")
    private UUID inviteUserId;

    @Column(name = "commission_buyer_user_id", updatable = false)
    private UUID commissionBuyerUserId;

    @Column(name = "commission_base", nullable = false)
    private long commissionBase;

    @Column(name = "commission_balance", nullable = false)
    private long commissionBalance;

    /** 0 pending, 1 confirmed, 2 paid, 3 invalid; null means ineligible. */
    @Column(name = "commission_status")
    private Integer commissionStatus;

    @Column(name = "actual_commission_balance", nullable = false)
    private long actualCommissionBalance;

    @Column(name = "coupon_id")
    private UUID couponId;

    /**
     * The payment method the customer chose, and the surcharge it added on top
     * of {@link #totalAmount}. Kept apart from the order's own total so what the
     * order is worth never depends on how it was paid for.
     */
    @Column(name = "payment_method_id")
    private UUID paymentMethodId;

    @Column(length = 32)
    private String gateway;

    @Column(name = "handling_amount", nullable = false)
    private long handlingAmount;

    @Column(
        name = "surplus_order_ids",
        nullable = false,
        columnDefinition = "text"
    )
    private String surplusOrderIds = "[]";

    /** Snapshot that this order was placed under the configured new-user offer. */
    @Column(name = "is_new_user_offer", nullable = false)
    private boolean newUserOffer;

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
        boolean newUserOffer,
        OrderDeductionMode deductionMode,
        long deferredSurplusCreditMinor,
        Instant resetCycleStart,
        Instant resetCycleEnd,
        UUID resetCycleId,
        Instant now
    ) {
        ServiceOrder order = new ServiceOrder();
        order.id = UUID.randomUUID();
        order.tradeNo = tradeNo;
        order.user = user;
        order.balancePayerUserId = user.getId();
        order.commissionBuyerUserId = user.getId();
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
        order.newUserOffer = newUserOffer;
        order.deductionMode = deductionMode == null
            ? OrderDeductionMode.STANDARD : deductionMode;
        order.deferredSurplusCreditMinor = Math.max(deferredSurplusCreditMinor, 0);
        order.resetCycleStart = resetCycleStart;
        order.resetCycleEnd = resetCycleEnd;
        order.resetCycleId = resetCycleId;
        // Every order starts unpaid, however much of it the discounts covered.
        // A total of zero is not a settled order: nothing is provisioned until
        // a payment - or an admin settling it by hand - moves it on.
        order.status = OrderStatus.PENDING;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /** Source-compatible factory for reset orders created before stable cycle identities. */
    public static ServiceOrder create(
        String tradeNo, UserAccount user, ServicePlan plan, BillingPeriod period,
        OrderType orderType, String currency, OrderPricing.Breakdown breakdown,
        UUID couponId, String surplusOrderIds, boolean newUserOffer,
        OrderDeductionMode deductionMode, long deferredSurplusCreditMinor,
        Instant resetCycleEnd, Instant now
    ) {
        return create(tradeNo, user, plan, period, orderType, currency, breakdown,
            couponId, surplusOrderIds, newUserOffer, deductionMode,
            deferredSurplusCreditMinor, null, resetCycleEnd, null, now);
    }

    /** Source-compatible factory before cycle identity snapshots were introduced. */
    public static ServiceOrder create(
        String tradeNo, UserAccount user, ServicePlan plan, BillingPeriod period,
        OrderType orderType, String currency, OrderPricing.Breakdown breakdown,
        UUID couponId, String surplusOrderIds, boolean newUserOffer,
        OrderDeductionMode deductionMode, long deferredSurplusCreditMinor,
        Instant resetCycleStart, Instant resetCycleEnd, Instant now
    ) {
        return create(tradeNo, user, plan, period, orderType, currency, breakdown,
            couponId, surplusOrderIds, newUserOffer, deductionMode,
            deferredSurplusCreditMinor, resetCycleStart, resetCycleEnd, null, now);
    }

    /** Source-compatible ordinary purchase factory. */
    public static ServiceOrder create(
        String tradeNo, UserAccount user, ServicePlan plan, BillingPeriod period,
        OrderType orderType, String currency, OrderPricing.Breakdown breakdown,
        UUID couponId, String surplusOrderIds, boolean newUserOffer, Instant now
    ) {
        return create(tradeNo, user, plan, period, orderType, currency, breakdown,
            couponId, surplusOrderIds, newUserOffer, OrderDeductionMode.STANDARD,
            0, null, now);
    }

    /** Keeps existing order fixtures source-compatible for ordinary purchases. */
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
        return create(
            tradeNo,
            user,
            plan,
            period,
            orderType,
            currency,
            breakdown,
            couponId,
            surplusOrderIds,
            false,
            now
        );
    }

    public void cancel(Instant now) {
        status = OrderStatus.CANCELLED;
        canceledAt = now;
        updatedAt = now;
    }

    /**
     * Hands the order to another account, as an administrator's correction
     * does. The pricing rows are untouched: the money was already accounted
     * against the original purchase, so only the owner changes.
     */
    public void assignTo(UserAccount target, Instant now) {
        this.user = target;
        this.updatedAt = now;
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

    /**
     * Records how the customer is paying, and what that adds to the bill.
     *
     * Fixed at checkout and re-read from the order when the callback arrives, so
     * the amount the gateway is told to collect and the amount it is checked
     * against come from the same place.
     */
    public void attachPayment(
        UUID paymentMethodId,
        String gateway,
        long handlingAmount,
        Instant now
    ) {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException(
                "Only a pending order can be checked out, was " + status
            );
        }
        if (handlingAmount < 0) {
            throw new IllegalArgumentException(
                "A handling fee cannot be negative"
            );
        }
        this.paymentMethodId = paymentMethodId;
        this.gateway = gateway;
        this.handlingAmount = handlingAmount;
        updatedAt = now;
    }

    /** What the customer is asked to pay: the order plus the surcharge. */
    public long payableAmount() {
        return totalAmount + handlingAmount;
    }

    public void complete(Instant now) {
        status = OrderStatus.COMPLETED;
        settlementOutcome = OrderSettlementOutcome.SERVICE_FULFILLED;
        updatedAt = now;
    }

    /** Closes a paid order by returning the captured amount as spendable site balance. */
    public void returnCapturedPaymentToBalance(long amountMinor, Instant now) {
        if (status != OrderStatus.PROCESSING || amountMinor < 0) {
            throw new IllegalStateException("Only a paid processing order can return captured payment");
        }
        status = OrderStatus.COMPLETED;
        settlementOutcome = OrderSettlementOutcome.BALANCE_RETURNED;
        returnedBalanceMinor = amountMinor;
        commissionStatus = commissionStatus == null ? null : 3;
        updatedAt = now;
    }

    /** The paid coverage represented by this order, anchored when fulfilment actually occurs. */
    public void snapshotCoverage(Instant start, Instant end) {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("A paid coverage segment needs an increasing interval");
        }
        coverageStart = start;
        coverageEnd = end;
    }

    /** FULL_PAYMENT settles its surplus credit from the resources left at fulfilment. */
    public void setDeferredSurplusCreditMinor(long amountMinor) {
        deferredSurplusCreditMinor = Math.max(amountMinor, 0);
    }

    /** Captures the eligible referral pool before any zero-balance fulfilment. */
    public void snapshotCommission(
        UUID inviterUserId,
        long baseMinor,
        long poolMinor,
        boolean eligible
    ) {
        this.inviteUserId = inviterUserId;
        this.commissionBase = Math.max(baseMinor, 0);
        this.commissionBalance = Math.max(poolMinor, 0);
        this.commissionStatus = inviterUserId == null
            ? null
            : eligible && poolMinor > 0 ? 0 : 3;
    }

    public void setCommissionStatus(Integer status, Instant now) {
        if (commissionStatus != null && commissionStatus == 2) {
            throw new IllegalStateException("Paid commission cannot be reset");
        }
        if (status == null || (status != 0 && status != 1 && status != 3)) {
            throw new IllegalArgumentException("Invalid commission status");
        }
        commissionStatus = status;
        updatedAt = now;
    }

    public void markCommissionPaid(long actualMinor) {
        if (commissionStatus == null || commissionStatus != 1) {
            throw new IllegalStateException("Only confirmed commission can be paid");
        }
        commissionStatus = 2;
        actualCommissionBalance = actualMinor;
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
        return settledValueBigInteger().longValueExact();
    }

    /** Exact funded value, suitable for prorating without overflowing minor-unit sums. */
    public BigInteger settledValueBigInteger() {
        return BigInteger.valueOf(totalAmount)
            .add(BigInteger.valueOf(balanceAmount))
            .add(BigInteger.valueOf(surplusAmount))
            .subtract(BigInteger.valueOf(surplusCredit));
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

    public UUID getBalancePayerUserId() {
        if (balancePayerUserId != null) {
            return balancePayerUserId;
        }
        return commissionBuyerUserId == null ? user.getId() : commissionBuyerUserId;
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

    public OrderDeductionMode getDeductionMode() {
        return deductionMode;
    }

    public long getDeferredSurplusCreditMinor() {
        return deferredSurplusCreditMinor;
    }

    public Instant getResetCycleEnd() {
        return resetCycleEnd;
    }

    public Instant getResetCycleStart() {
        return resetCycleStart;
    }

    public UUID getResetCycleId() {
        return resetCycleId;
    }

    public OrderSettlementOutcome getSettlementOutcome() {
        return settlementOutcome;
    }

    public long getReturnedBalanceMinor() {
        return returnedBalanceMinor;
    }

    public Instant getCoverageStart() {
        return coverageStart;
    }

    public Instant getCoverageEnd() {
        return coverageEnd;
    }

    public long getBalanceAmount() {
        return balanceAmount;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public UUID getInviteUserId() {
        return inviteUserId;
    }

    public UUID getCommissionBuyerUserId() {
        return commissionBuyerUserId == null ? user.getId() : commissionBuyerUserId;
    }

    public long getCommissionBase() {
        return commissionBase;
    }

    public long getCommissionBalance() {
        return commissionBalance;
    }

    public Integer getCommissionStatus() {
        return commissionStatus;
    }

    public long getActualCommissionBalance() {
        return actualCommissionBalance;
    }

    public UUID getCouponId() {
        return couponId;
    }

    public UUID getPaymentMethodId() {
        return paymentMethodId;
    }

    public String getGateway() {
        return gateway;
    }

    public long getHandlingAmount() {
        return handlingAmount;
    }

    /** The orders a later upgrade consumed, as a JSON array of ids. */
    public String getSurplusOrderIds() {
        return surplusOrderIds;
    }

    public boolean isNewUserOffer() {
        return newUserOffer;
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

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
