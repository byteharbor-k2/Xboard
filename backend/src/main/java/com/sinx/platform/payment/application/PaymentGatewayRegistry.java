package com.sinx.platform.payment.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * The gateway implementations this build knows how to speak.
 *
 * A stored method names its gateway by code, so retiring an implementation
 * leaves its rows readable - they simply stop resolving, and say so plainly
 * rather than failing somewhere deeper.
 *
 * Codes are matched without regard to case. The original panel writes them into
 * its own column and lower-cases them again for the callback URL, so rows
 * carried over from it - or written by hand - spell the same gateway differently
 * and still have to work.
 */
@Component
public class PaymentGatewayRegistry {

    private static final Locale NO_LOCALE = Locale.ROOT;

    private final Map<String, PaymentGateway> gateways;

    PaymentGatewayRegistry(List<PaymentGateway> gateways) {
        Map<String, PaymentGateway> byCode = new LinkedHashMap<>();
        for (PaymentGateway gateway : gateways) {
            PaymentGateway previous =
                byCode.put(key(gateway.code()), gateway);
            if (previous != null) {
                throw new IllegalStateException(
                    "Two gateways claim the code " + gateway.code()
                );
            }
        }
        this.gateways = Map.copyOf(byCode);
    }

    public PaymentGateway require(String code) {
        PaymentGateway gateway = code == null ? null : gateways.get(key(code));
        if (gateway == null) {
            throw new ApiProblemException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "PAYMENT_GATEWAY_UNSUPPORTED",
                "This build has no payment gateway called " + code
            );
        }
        return gateway;
    }

    /** Every gateway an administrator may configure, in a stable order. */
    public List<PaymentGateway> all() {
        return List.copyOf(gateways.values());
    }

    private static String key(String code) {
        return code == null ? "" : code.toLowerCase(NO_LOCALE);
    }
}
