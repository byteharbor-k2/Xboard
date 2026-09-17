package com.sinx.platform.payment.domain;

/**
 * How the browser should be sent to the gateway.
 *
 * {@code type} keeps the original panel's meaning: 1 is a URL to redirect to,
 * 0 is a payload to render as a QR code. Only 1 is produced today - Epay's
 * cashier is a redirect - but the shape is left as the original has it so a
 * QR gateway can be added without touching the contract.
 */
public record PaymentRedirect(int type, String data) {

    public static final int REDIRECT = 1;

    public static PaymentRedirect to(String url) {
        return new PaymentRedirect(REDIRECT, url);
    }
}
