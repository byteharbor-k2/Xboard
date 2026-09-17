package com.sinx.platform.payment.domain;

import java.util.List;
import java.util.Map;

/**
 * A payment protocol the platform can speak.
 *
 * Implementations are stateless: everything a gateway needs to know about a
 * merchant account arrives as a configuration map, because the same gateway
 * code is configured more than once - one Epay endpoint for WeChat and Alipay,
 * another for crypto.
 *
 * Nothing here talks to the network. {@code pay} builds a redirect and
 * {@code verify} checks a signature; the customer's browser and the gateway's
 * callback do the travelling.
 */
public interface PaymentGateway {

    /** The stable code stored on {@link PaymentMethod#getGateway()}. */
    String code();

    /** The inputs an administrator has to fill in, in the order to show them. */
    List<GatewayField> form();

    /** Builds the redirect that starts a payment. */
    PaymentRedirect pay(Map<String, String> config, PaymentContext context);

    /**
     * Checks a callback's signature and reads what it says was paid.
     *
     * @throws PaymentVerificationException if the callback cannot be trusted
     */
    PaymentNotification verify(
        Map<String, String> config,
        Map<String, String> params
    );
}
