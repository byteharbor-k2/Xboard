package com.sinx.platform.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A verified gateway transaction recorded atomically with its money effect. */
@Entity
@Table(name = "payment_receipts")
public class PaymentReceipt {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "attempt_id", updatable = false)
    private UUID attemptId;

    @Column(name = "gateway", nullable = false, length = 32, updatable = false)
    private String gateway;

    @Column(name = "gateway_url", nullable = false, length = 512, updatable = false)
    private String gatewayUrl;

    @Column(name = "merchant_identity", nullable = false, length = 120, updatable = false)
    private String merchantIdentity;

    @Column(name = "transaction_id", nullable = false, length = 128, updatable = false)
    private String transactionId;

    @Column(name = "trade_no", nullable = false, length = 32, updatable = false)
    private String tradeNo;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "outcome", nullable = false, length = 24, updatable = false)
    private String outcome;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected PaymentReceipt() {
    }

    public static PaymentReceipt create(
        PaymentAttempt attempt,
        String transactionId,
        long amountMinor,
        Outcome outcome,
        Instant receivedAt
    ) {
        if (transactionId == null || transactionId.isBlank() || amountMinor <= 0) {
            throw new IllegalArgumentException("A receipt needs a transaction id and positive amount");
        }
        PaymentReceipt receipt = new PaymentReceipt();
        receipt.id = UUID.randomUUID();
        receipt.attemptId = attempt.getId();
        receipt.gateway = attempt.getGateway();
        receipt.gatewayUrl = attempt.getGatewayUrl();
        receipt.merchantIdentity = attempt.getMerchantIdentity();
        receipt.transactionId = transactionId;
        receipt.tradeNo = attempt.getTradeNo();
        receipt.amountMinor = amountMinor;
        receipt.outcome = outcome.name();
        receipt.receivedAt = receivedAt;
        return receipt;
    }

    public enum Outcome {
        ORDER_SETTLED,
        BALANCE_CREDITED
    }

    public String getGateway() {
        return gateway;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getTradeNo() {
        return tradeNo;
    }

    public long getAmountMinor() {
        return amountMinor;
    }
}
