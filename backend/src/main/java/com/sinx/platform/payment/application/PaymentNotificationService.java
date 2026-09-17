package com.sinx.platform.payment.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.payment.domain.PaymentMethod;
import com.sinx.platform.payment.domain.PaymentNotification;
import com.sinx.platform.payment.domain.PaymentVerificationException;

/**
 * Turns a gateway's callback into an opened order.
 *
 * This is the only path by which money moves an order on, so it is deliberately
 * suspicious: the signature has to check out, the amount has to be the amount the
 * order was checked out at, and the method has to be one that is switched on.
 * The original verifies the signature and nothing else, which lets a gateway
 * (or anything that has ever held the merchant key) report one yuan against a
 * thousand-yuan order and have it opened.
 *
 * Idempotent, as the original is. A callback that arrives twice opens one order;
 * one that arrives after the order was called off is logged and dropped.
 */
@Service
public class PaymentNotificationService {

    private static final Logger log =
        LoggerFactory.getLogger(PaymentNotificationService.class);

    private final PaymentMethodService methodService;
    private final OrderFulfilmentService fulfilment;
    private final ServiceOrderRepository orders;
    private final PaymentGatewayRegistry gateways;
    private final PaymentConfigCodec codec;

    PaymentNotificationService(
        PaymentMethodService methodService,
        OrderFulfilmentService fulfilment,
        ServiceOrderRepository orders,
        PaymentGatewayRegistry gateways,
        PaymentConfigCodec codec
    ) {
        this.methodService = methodService;
        this.fulfilment = fulfilment;
        this.orders = orders;
        this.gateways = gateways;
        this.codec = codec;
    }

    /**
     * Verifies a callback and settles the order it names.
     *
     * @return the order as it now stands, or null if the callback named one that
     *     does not exist
     * @throws PaymentVerificationException if the callback cannot be trusted
     */
    @Transactional
    public ServiceOrder accept(
        String gatewayCode,
        String uuid,
        Map<String, String> params
    ) {
        PaymentMethod method = methodService.requireEnabledByUuid(
            uuid,
            gatewayCode
        );
        PaymentGateway gateway = gateways.require(method.getGateway());
        PaymentNotification notification = gateway.verify(
            codec.read(method.getConfig()),
            params
        );

        ServiceOrder order = orders
            .findByTradeNoForUpdate(notification.tradeNo())
            .orElse(null);
        if (order == null) {
            // Retrying will not conjure the order up, so this is answered as a
            // success rather than left for the gateway to keep resending.
            log.warn(
                "A verified {} callback named an order that does not exist: {}",
                gatewayCode,
                notification.tradeNo()
            );
            return null;
        }
        if (!order.isPending()) {
            if (order.getStatus() == OrderStatus.CANCELLED) {
                // Paid after the expiry sweep called the order off. The customer
                // has been told to order again, and reviving an order here would
                // open a subscription against a purchase that was already
                // refunded as surplus.
                log.warn(
                    "A payment arrived for order {} after it was cancelled; "
                        + "the order is left cancelled and the payment is not "
                        + "applied",
                    order.getTradeNo()
                );
            }
            // Otherwise this is a repeat of a callback already applied. The
            // original reports success and does nothing.
            return order;
        }

        requireAmountPaid(order, notification);
        return fulfilment.settle(
            notification.tradeNo(),
            notification.callbackNo()
        );
    }

    /**
     * The signature proves the gateway sent the message; only this proves the
     * gateway was told to collect what the order actually costs.
     */
    private void requireAmountPaid(
        ServiceOrder order,
        PaymentNotification notification
    ) {
        BigDecimal due = BigDecimal.valueOf(order.payableAmount())
            .movePointLeft(2)
            .setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = notification.amount()
            .setScale(2, RoundingMode.HALF_UP);
        if (paid.compareTo(due) != 0) {
            log.warn(
                "A callback for order {} reported {} but {} was due; refusing "
                    + "to open it",
                order.getTradeNo(),
                paid.toPlainString(),
                due.toPlainString()
            );
            throw new PaymentVerificationException(
                "The amount reported does not match the order"
            );
        }
    }
}
