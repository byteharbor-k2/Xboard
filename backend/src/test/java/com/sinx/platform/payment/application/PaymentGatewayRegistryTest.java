package com.sinx.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sinx.platform.payment.domain.GatewayField;
import com.sinx.platform.payment.domain.PaymentContext;
import com.sinx.platform.payment.domain.PaymentGateway;
import com.sinx.platform.payment.domain.PaymentNotification;
import com.sinx.platform.payment.domain.PaymentRedirect;
import com.sinx.platform.shared.web.ApiProblemException;

/**
 * How a stored method's gateway code is resolved.
 *
 * The codes come out of a database column that the original panel also wrote,
 * so the spelling is not ours to control: a row may say {@code EPay} or
 * {@code epay} and a customer's checkout still has to work.
 */
class PaymentGatewayRegistryTest {

    @Test
    void resolvesAGatewayWhicheverWayItsCodeIsSpelled() {
        PaymentGatewayRegistry registry =
            new PaymentGatewayRegistry(List.of(new StubGateway("EPay")));

        assertThat(registry.require("EPay").code()).isEqualTo("EPay");
        assertThat(registry.require("epay").code()).isEqualTo("EPay");
        assertThat(registry.require("EPAY").code()).isEqualTo("EPay");
    }

    @Test
    void reportsAnUnknownGatewayAsAnUnprocessableProblem() {
        PaymentGatewayRegistry registry =
            new PaymentGatewayRegistry(List.of(new StubGateway("EPay")));

        assertThatThrownBy(() -> registry.require("Bitcoin"))
            .isInstanceOf(ApiProblemException.class)
            .hasMessageContaining("Bitcoin");
        assertThatThrownBy(() -> registry.require(null))
            .isInstanceOf(ApiProblemException.class);
    }

    /** Two implementations of one gateway is a wiring mistake, not a choice. */
    @Test
    void refusesTwoGatewaysClaimingTheSameCodeInAnyCase() {
        assertThatThrownBy(() -> new PaymentGatewayRegistry(
            List.of(new StubGateway("EPay"), new StubGateway("epay"))
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("epay");
    }

    /** Only the code matters here; the rest of the contract is never reached. */
    private record StubGateway(String code) implements PaymentGateway {

        @Override
        public List<GatewayField> form() {
            return List.of();
        }

        @Override
        public PaymentRedirect pay(
            Map<String, String> config,
            PaymentContext context
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PaymentNotification verify(
            Map<String, String> config,
            Map<String, String> params
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
