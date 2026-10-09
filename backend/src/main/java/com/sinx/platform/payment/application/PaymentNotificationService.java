package com.sinx.platform.payment.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.balance.application.BalanceLedgerService;
import com.sinx.platform.balance.domain.BalanceLogType;
import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.domain.OrderSettlementOutcome;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.domain.PaymentAttempt;
import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.payment.domain.PaymentNotification;
import com.sinx.platform.payment.domain.PaymentReceipt;
import com.sinx.platform.payment.domain.PaymentVerificationException;
import com.sinx.platform.payment.repository.PaymentAttemptRepository;
import com.sinx.platform.payment.repository.PaymentReceiptRepository;

/**
 * Turns a verified gateway receipt into either the order it paid for or
 * spendable balance. Every actual gateway transaction is recorded in the same
 * transaction as its settlement/credit, so a retry cannot lose or duplicate
 * money even when an order has since been cancelled or settled manually.
 */
@Service
public class PaymentNotificationService {

    private static final Logger log =
        LoggerFactory.getLogger(PaymentNotificationService.class);

    private final OrderFulfilmentService fulfilment;
    private final ServiceOrderRepository orders;
    private final PaymentGatewayRegistry gateways;
    private final PaymentConfigCodec codec;
    private final PaymentAttemptRepository attempts;
    private final PaymentReceiptRepository receipts;
    private final BalanceLedgerService balanceLedger;
    private final Clock clock;

    public PaymentNotificationService(
        OrderFulfilmentService fulfilment,
        ServiceOrderRepository orders,
        PaymentGatewayRegistry gateways,
        PaymentConfigCodec codec,
        PaymentAttemptRepository attempts,
        PaymentReceiptRepository receipts,
        BalanceLedgerService balanceLedger,
        Clock clock
    ) {
        this.fulfilment = fulfilment;
        this.orders = orders;
        this.gateways = gateways;
        this.codec = codec;
        this.attempts = attempts;
        this.receipts = receipts;
        this.balanceLedger = balanceLedger;
        this.clock = clock;
    }

    /**
     * Verifies against the immutable credentials used for a cashier link, then
     * reconciles its actual gateway transaction exactly once.
     *
     * @return the order as it now stands
     * @throws PaymentVerificationException if no matching attempt or trusted
     *     receipt can be established
     */
    @Transactional
    public ServiceOrder accept(
        String gatewayCode,
        String uuid,
        Map<String, String> params
    ) {
        String tradeNo = params.get("out_trade_no");
        if (tradeNo == null || tradeNo.isBlank()) {
            throw new PaymentVerificationException("The callback names no order");
        }
        List<PaymentAttempt> candidates = attempts
            .findByTradeNoAndMethodUuidAndGatewayIgnoreCaseOrderByCreatedAtDesc(
                tradeNo,
                uuid,
                gatewayCode
            );
        VerifiedAttempt verified = verifyAgainstAttempt(candidates, params);
        PaymentAttempt attempt = verified.attempt();
        PaymentNotification notification = verified.notification();
        long capturedMinor = toMinorUnits(notification.amount());

        ServiceOrder order = orders.findByTradeNoForUpdate(notification.tradeNo())
            .orElseThrow(() -> new PaymentVerificationException(
                "The callback names no recorded order"
            ));

        var prior = receipts
            .findByGatewayIgnoreCaseAndGatewayUrlAndMerchantIdentityAndTransactionId(
                attempt.getGateway(),
                attempt.getGatewayUrl(),
                attempt.getMerchantIdentity(),
                notification.callbackNo()
            );
        if (prior.isPresent()) {
            PaymentReceipt existing = prior.get();
            if (!existing.getTradeNo().equals(notification.tradeNo())
                    || existing.getAmountMinor() != capturedMinor) {
                throw new PaymentVerificationException(
                    "The gateway transaction was already recorded with different details"
                );
            }
            return order;
        }

        Instant now = Instant.now(clock);
        boolean amountMatchesAttempt = capturedMinor == attempt.getPayableAmountMinor();
        boolean checkoutOwnerStillOwnsOrder = attempt.getBuyerUserId()
            .equals(order.getUser().getId());
        boolean canSettle = order.isPending()
            && amountMatchesAttempt
            && checkoutOwnerStillOwnsOrder
            && order.getTotalAmount() == attempt.getOrderAmountMinor()
            && order.getCurrency().equals(attempt.getCurrency());

        PaymentReceipt.Outcome outcome;
        if (canSettle) {
            // Fulfilment can return the captured reset payment to balance. Bind
            // the order's current display/settlement fields back to the attempt
            // that actually received this receipt before that calculation runs.
            order.attachPayment(
                attempt.getMethodId(),
                attempt.getGateway(),
                attempt.getHandlingFeeMinor(),
                now
            );
            ServiceOrder settled = fulfilment.settle(
                order.getTradeNo(), notification.callbackNo());
            outcome = settled.getSettlementOutcome()
                    == OrderSettlementOutcome.BALANCE_RETURNED
                ? PaymentReceipt.Outcome.BALANCE_CREDITED
                : PaymentReceipt.Outcome.ORDER_SETTLED;
        } else {
            // A valid signed transaction is money received, even when it is
            // short/overpaid or the order has been cancelled or manually paid.
            // Credit the original checkout owner rather than silently dropping
            // the receipt or reviving a purchase that is no longer payable.
            balanceLedger.credit(
                attempt.getBuyerUserId(),
                capturedMinor,
                BalanceLogType.ORDER_REFUND,
                null,
                null,
                now
            );
            outcome = PaymentReceipt.Outcome.BALANCE_CREDITED;
            if (!amountMatchesAttempt) {
                log.warn(
                    "Verified gateway receipt for order {} was {} minor units; "
                        + "the immutable cashier attempt expected {} and the "
                        + "captured amount was credited to balance",
                    order.getTradeNo(),
                    capturedMinor,
                    attempt.getPayableAmountMinor()
                );
            } else if (!checkoutOwnerStillOwnsOrder) {
                log.warn(
                    "A verified receipt for order {} arrived after ownership "
                        + "changed; the captured amount was credited to the "
                        + "original checkout owner",
                    order.getTradeNo()
                );
            } else if (!order.isPending()) {
                log.warn(
                    "A verified payment arrived after order {} was {}; the "
                        + "captured amount was credited to balance",
                    order.getTradeNo(),
                    order.getStatus()
                );
            }
        }

        // Unique gateway instance/account/transaction identity is the final
        // concurrency boundary across orders and concurrent callbacks.
        // If a simultaneous callback already claimed this receipt, this insert
        // fails and rolls back the accompanying settlement or ledger credit.
        receipts.saveAndFlush(PaymentReceipt.create(
            attempt,
            notification.callbackNo(),
            capturedMinor,
            outcome,
            now
        ));
        return order;
    }

    private VerifiedAttempt verifyAgainstAttempt(
        List<PaymentAttempt> candidates,
        Map<String, String> params
    ) {
        PaymentVerificationException lastFailure = null;
        VerifiedAttempt firstValid = null;
        for (PaymentAttempt candidate : candidates) {
            PaymentGateway gateway = gateways.require(candidate.getGateway());
            try {
                PaymentNotification notification = gateway.verify(
                    codec.read(candidate.getMerchantConfig()), params);
                VerifiedAttempt verified = new VerifiedAttempt(candidate, notification);
                if (toMinorUnits(notification.amount()) == candidate.getPayableAmountMinor()) {
                    return verified;
                }
                if (firstValid == null) {
                    firstValid = verified;
                }
            } catch (PaymentVerificationException rejected) {
                lastFailure = rejected;
            }
        }
        if (firstValid != null) {
            return firstValid;
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new PaymentVerificationException(
            "No checkout attempt matches this callback method"
        );
    }

    /** Converts a signed CNY amount to cents without rounding away value. */
    private long toMinorUnits(BigDecimal amount) {
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2)
                .longValueExact();
        } catch (ArithmeticException invalidAmount) {
            throw new PaymentVerificationException(
                "The callback amount cannot be represented in CNY minor units"
            );
        }
    }

    private record VerifiedAttempt(
        PaymentAttempt attempt,
        PaymentNotification notification
    ) {
    }
}
