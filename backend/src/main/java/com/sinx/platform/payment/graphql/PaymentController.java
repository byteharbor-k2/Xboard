package com.sinx.platform.payment.graphql;

import java.util.List;
import java.util.UUID;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

import com.sinx.platform.payment.application.PaymentCheckoutService;
import com.sinx.platform.payment.application.PaymentOptionView;
import com.sinx.platform.payment.domain.PaymentRedirect;

/**
 * Paying for an order, on the customer-facing schema.
 *
 * These sit next to the order queries rather than on a REST surface of their
 * own, so the browser has one place to talk to and one place to be refused.
 */
@Controller
public class PaymentController {

    private final PaymentCheckoutService checkout;

    public PaymentController(PaymentCheckoutService checkout) {
        this.checkout = checkout;
    }

    /** The ways this order can be paid for, each priced for it. */
    @QueryMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    List<PaymentOptionView> paymentOptions(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String tradeNo
    ) {
        return checkout.options(UUID.fromString(jwt.getSubject()), tradeNo);
    }

    /**
     * Records the chosen method and answers where to send the customer.
     *
     * The customer is sent to the gateway rather than the gateway being called:
     * Epay's cashier is a page the customer's own browser has to open, so what
     * comes back is a URL, not a result.
     */
    @MutationMapping
    @PreAuthorize("hasRole('USER') and hasAuthority('SCOPE_USER')")
    PaymentRedirectPayload checkoutOrder(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String tradeNo,
        @Argument UUID paymentMethodId
    ) {
        PaymentRedirect redirect = checkout.checkout(
            UUID.fromString(jwt.getSubject()),
            tradeNo,
            paymentMethodId
        );
        return new PaymentRedirectPayload(redirect.type(), redirect.data());
    }

    public record PaymentRedirectPayload(int type, String data) {
    }
}
