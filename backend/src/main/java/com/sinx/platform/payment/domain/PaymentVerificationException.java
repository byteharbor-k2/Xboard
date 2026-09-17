package com.sinx.platform.payment.domain;

/**
 * A callback that did not come from the gateway, or did not say what it has to
 * say to be believable. Callers must not open an order on one of these.
 */
public class PaymentVerificationException extends RuntimeException {

    public PaymentVerificationException(String message) {
        super(message);
    }
}
