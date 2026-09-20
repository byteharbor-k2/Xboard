package com.sinx.platform.payment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.order.application.OrderFulfilmentService;
import com.sinx.platform.order.domain.ServiceOrder;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.payment.domain.PaymentContext;
import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.payment.domain.PaymentMethod;
import com.sinx.platform.payment.domain.PaymentRedirect;
import com.sinx.platform.payment.repository.PaymentMethodRepository;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * Takes a pending order to a gateway.
 *
 * Everything that decides how much is charged is read from the order and the
 * stored method; nothing about the amount comes from the request. The order is
 * locked while it is being checked out, so a customer with two tabs open cannot
 * have a surcharge written twice, and the amount the gateway is asked to collect
 * is the amount the callback will be checked against.
 */
@Service
public class PaymentCheckoutService {

    private final ServiceOrderRepository orders;
    private final PaymentMethodRepository methods;
    private final PaymentMethodService methodService;
    private final PaymentGatewayRegistry gateways;
    private final PaymentConfigCodec codec;
    private final OrderFulfilmentService fulfilment;
    private final Clock clock;

    public PaymentCheckoutService(
        ServiceOrderRepository orders,
        PaymentMethodRepository methods,
        PaymentMethodService methodService,
        PaymentGatewayRegistry gateways,
        PaymentConfigCodec codec,
        OrderFulfilmentService fulfilment,
        Clock clock
    ) {
        this.orders = orders;
        this.methods = methods;
        this.methodService = methodService;
        this.gateways = gateways;
        this.codec = codec;
        this.fulfilment = fulfilment;
        this.clock = clock;
    }

    /**
     * The methods this order may be paid with, each priced for it.
     *
     * Empty for an order that is already settled, and for one whose deductions
     * brought it to nothing. Such an order is opened here rather than being
     * offered methods, since there is nothing left for a gateway to collect;
     * the caller learns that from the empty list.
     *
     * The opening happens here as well as at checkout because orders placed
     * before this rule existed are still sitting pending with nothing the
     * customer could do about them. Settling is idempotent, so an order that is
     * no longer pending is simply left alone.
     */
    @Transactional
    public List<PaymentOptionView> options(UUID userId, String tradeNo) {
        ServiceOrder order = requireOwnOrder(userId, tradeNo);
        // The fee is quoted against the order's own total, never against a
        // total that already carries a fee, or re-opening a checked-out order
        // would charge the surcharge twice.
        if (order.getTotalAmount() <= 0) {
            if (order.isPending()) {
                fulfilment.settleCovered(order.getTradeNo());
            }
            return List.of();
        }
        return methods.findByEnabledTrueOrderBySortOrderAscCreatedAtAsc().stream()
            .map(method -> PaymentOptionView.of(
                method,
                order.getTotalAmount(),
                order.getCurrency()
            ))
            .toList();
    }

    /**
     * Records how the customer is paying and returns where to send them.
     *
     * The handling fee, the recorded method and the gateway redirect are all
     * written in one transaction: an order cannot end up pointing at a method it
     * was never actually checked out with.
     */
    @Transactional
    public PaymentRedirect checkout(
        UUID userId,
        String tradeNo,
        UUID paymentMethodId
    ) {
        ServiceOrder order = orders.findByTradeNoForUpdate(tradeNo)
            .filter(candidate -> candidate.getUser().getId().equals(userId))
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            ));
        if (!order.isPending()) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_NOT_PENDING",
                "Only an order awaiting payment can be checked out"
            );
        }
        if (order.getTotalAmount() <= 0) {
            throw problem(
                HttpStatus.CONFLICT,
                "ORDER_NOT_PAYABLE",
                "This order has nothing left to pay; it is opened by an "
                    + "administrator instead"
            );
        }
        PaymentMethod method = methodService.requireEnabled(paymentMethodId);

        long handlingFee = method.handlingFeeFor(order.getTotalAmount());
        order.attachPayment(
            method.getId(),
            method.getGateway(),
            handlingFee,
            Instant.now(clock)
        );

        PaymentGateway gateway = gateways.require(method.getGateway());
        return gateway.pay(
            codec.read(method.getConfig()),
            new PaymentContext(
                order.getTradeNo(),
                order.payableAmount(),
                order.getCurrency(),
                methodService.notifyUrl(method),
                methodService.returnUrl(order.getTradeNo())
            )
        );
    }

    private ServiceOrder requireOwnOrder(UUID userId, String tradeNo) {
        return orders.findByTradeNo(tradeNo)
            .filter(order -> order.getUser().getId().equals(userId))
            .orElseThrow(() -> problem(
                HttpStatus.NOT_FOUND,
                "ORDER_NOT_FOUND",
                "The order does not exist"
            ));
    }

    private ApiProblemException problem(
        HttpStatus status,
        String code,
        String detail
    ) {
        return new ApiProblemException(status, code, detail);
    }
}
