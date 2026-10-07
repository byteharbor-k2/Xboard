package com.sinx.platform.balance.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One signed, durable cash movement, or the opening balance anchor at cutover. */
@Entity
@Table(name = "balance_logs")
public class BalanceLog {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Database insertion order; user-row locking makes this the cash-mutation order. */
    @Column(name = "ledger_sequence", nullable = false, insertable = false, updatable = false)
    private long ledgerSequence;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private BalanceLogType type;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "balance_after_minor", nullable = false, updatable = false)
    private long balanceAfterMinor;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "trade_no", length = 32, updatable = false)
    private String tradeNo;

    @Column(name = "commission_level", updatable = false)
    private Integer commissionLevel;

    /** Optional customer-visible explanation for an administrator adjustment. */
    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BalanceLog() {
    }

    public static BalanceLog create(
        UUID userId,
        BalanceLogType type,
        long amountMinor,
        long balanceAfterMinor,
        String currency,
        String tradeNo,
        Integer commissionLevel,
        String note,
        Instant createdAt
    ) {
        if (userId == null || type == null || amountMinor == 0
                && type != BalanceLogType.OPENING_BALANCE
                || currency == null || currency.length() != 3 || createdAt == null) {
            throw new IllegalArgumentException("A balance ledger record is incomplete");
        }
        if (type == BalanceLogType.COMMISSION_CREDIT
                != (commissionLevel != null && commissionLevel >= 1 && commissionLevel <= 3)) {
            throw new IllegalArgumentException("A commission record needs its distribution level");
        }
        if (type == BalanceLogType.OPENING_BALANCE
                && balanceAfterMinor != amountMinor) {
            throw new IllegalArgumentException("An opening record must anchor the opening balance");
        }
        BalanceLog log = new BalanceLog();
        log.id = UUID.randomUUID();
        log.userId = userId;
        log.type = type;
        log.amountMinor = amountMinor;
        log.balanceAfterMinor = balanceAfterMinor;
        log.currency = currency;
        log.tradeNo = tradeNo;
        log.commissionLevel = commissionLevel;
        log.note = note;
        log.createdAt = createdAt;
        return log;
    }

    public UUID getId() { return id; }
    public long getLedgerSequence() { return ledgerSequence; }
    public UUID getUserId() { return userId; }
    public BalanceLogType getType() { return type; }
    public long getAmountMinor() { return amountMinor; }
    public long getBalanceAfterMinor() { return balanceAfterMinor; }
    public String getCurrency() { return currency; }
    public String getTradeNo() { return tradeNo; }
    public Integer getCommissionLevel() { return commissionLevel; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
