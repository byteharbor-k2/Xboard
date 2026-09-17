package com.sinx.platform.payment.domain;

import java.math.BigDecimal;

/**
 * A callback that has been verified as genuine.
 *
 * {@code amount} is what the gateway says was actually paid. It is checked
 * against the order before the order is opened: the signature proves the
 * gateway sent the message, but only the amount proves it was paid in full.
 */
public record PaymentNotification(
    String tradeNo,
    String callbackNo,
    BigDecimal amount
) {
}
