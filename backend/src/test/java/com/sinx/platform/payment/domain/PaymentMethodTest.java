package com.sinx.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * The handling fee, which is the one piece of arithmetic the payment path does
 * on the customer's behalf.
 *
 * It has to come out the same on three separate occasions - when the customer is
 * quoted, when the gateway is asked to collect, and when the callback is checked
 * - so it is rounded once, in one place, rather than at each of them.
 */
class PaymentMethodTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    @Test
    void chargesNothingWhenNoFeeIsConfigured() {
        assertThat(method(null, null).handlingFeeFor(1200)).isZero();
    }

    @Test
    void addsAPercentageOfTheOrder() {
        assertThat(method(null, new BigDecimal("2.5")).handlingFeeFor(1200))
            .isEqualTo(30);
    }

    @Test
    void addsAFixedAmount() {
        assertThat(method(100L, null).handlingFeeFor(1200)).isEqualTo(100);
    }

    @Test
    void addsBothPartsAndRoundsTheTotalOnce() {
        // 1234 * 2.5% = 30.85, plus 100 = 130.85, rounded half up to 131 - the
        // original's round(total * percent / 100 + fixed) exactly.
        assertThat(method(100L, new BigDecimal("2.5")).handlingFeeFor(1234))
            .isEqualTo(131);
    }

    @Test
    void roundsAHalfUpRatherThanToEven() {
        // 4 * 12.5% = 0.5, which the original's round() takes to 1.
        assertThat(method(null, new BigDecimal("12.5")).handlingFeeFor(4))
            .isEqualTo(1);
    }

    @Test
    void chargesNothingOnAnOrderWithNothingLeftToPay() {
        assertThat(method(100L, new BigDecimal("2.5")).handlingFeeFor(0))
            .isZero();
    }

    @Test
    void keepsANameThatIsGivenAndRefusesOneThatIsNot() {
        assertThat(PaymentMethod.create("EPay", "  WeChat  ", NOW).getName())
            .isEqualTo("WeChat");

        assertThatThrownBy(() -> PaymentMethod.create("EPay", "   ", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("needs a name");
        assertThatThrownBy(() -> PaymentMethod.create(
            "EPay",
            "x".repeat(121),
            NOW
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("120 characters");
    }

    @Test
    void startsSwitchedOffWithAnUnguessableCallbackToken() {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);

        assertThat(method.isEnabled()).isFalse();
        assertThat(method.getUuid()).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(method.is("EPay")).isTrue();
        // The stored spelling is not the identity: the original panel writes
        // EPay into its column and lower-cases it again for the callback URL.
        assertThat(method.is("epay")).isTrue();
        assertThat(method.is("EPAY")).isTrue();
        assertThat(method.is("Bitcoin")).isFalse();
        assertThat(method.is(null)).isFalse();
    }

    @Test
    void flipsItsSwitchRatherThanBeingToldWhatStateToBeIn() {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);

        method.toggle(NOW);
        assertThat(method.isEnabled()).isTrue();
        method.toggle(NOW);
        assertThat(method.isEnabled()).isFalse();
    }

    /** The order here is the order a customer sees their choices in. */
    @Test
    void takesItsPlaceInTheListWhenAnAdministratorReordersIt() {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);

        method.moveTo(3, NOW);

        assertThat(method.getSortOrder()).isEqualTo(3);
    }

    private PaymentMethod method(Long fixed, BigDecimal percent) {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);
        method.describe(null, null, fixed, percent, NOW);
        return method;
    }
}
