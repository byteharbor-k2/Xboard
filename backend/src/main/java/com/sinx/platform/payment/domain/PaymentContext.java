package com.sinx.platform.payment.domain;

/**
 * What a gateway needs to start a payment.
 *
 * {@code amountMinor} is what the customer actually pays, handling fee
 * included; it is computed on the server from the order and the gateway is
 * never told a number the customer could influence.
 */
public record PaymentContext(
    String tradeNo,
    long amountMinor,
    String currency,
    String notifyUrl,
    String returnUrl
) {
}
