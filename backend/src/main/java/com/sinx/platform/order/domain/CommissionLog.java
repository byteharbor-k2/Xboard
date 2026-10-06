package com.sinx.platform.order.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A durable record of referral value actually credited to a site's balance. */
@Entity
@Table(name = "commission_logs")
public class CommissionLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "invite_user_id", nullable = false, updatable = false)
    private UUID recipientUserId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID buyerUserId;

    @Column(name = "trade_no", nullable = false, updatable = false, length = 32)
    private String tradeNo;

    @Column(name = "order_amount", nullable = false, updatable = false)
    private long orderAmountMinor;

    @Column(name = "commission_base", nullable = false, updatable = false)
    private long commissionBaseMinor;

    @Column(name = "get_amount", nullable = false, updatable = false)
    private long amountMinor;

    @Column(nullable = false, updatable = false)
    private int level;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CommissionLog() {
    }

    public static CommissionLog create(
        UUID recipientUserId,
        UUID buyerUserId,
        String tradeNo,
        long orderAmountMinor,
        long commissionBaseMinor,
        long amountMinor,
        int level,
        Instant createdAt
    ) {
        CommissionLog log = new CommissionLog();
        log.id = UUID.randomUUID();
        log.recipientUserId = recipientUserId;
        log.buyerUserId = buyerUserId;
        log.tradeNo = tradeNo;
        log.orderAmountMinor = orderAmountMinor;
        log.commissionBaseMinor = commissionBaseMinor;
        log.amountMinor = amountMinor;
        log.level = level;
        log.createdAt = createdAt;
        return log;
    }

    public UUID getId() { return id; }
    public UUID getRecipientUserId() { return recipientUserId; }
    public UUID getBuyerUserId() { return buyerUserId; }
    public String getTradeNo() { return tradeNo; }
    public long getOrderAmountMinor() { return orderAmountMinor; }
    public long getCommissionBaseMinor() { return commissionBaseMinor; }
    public long getAmountMinor() { return amountMinor; }
    public int getLevel() { return level; }
    public Instant getCreatedAt() { return createdAt; }
}
