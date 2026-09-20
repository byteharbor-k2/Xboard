package com.sinx.platform.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.sinx.platform.payment.domain.PaymentMethod;

/**
 * What a customer is shown about a fee before choosing it.
 *
 * The quote has to carry the fee's own parts, not just its total: the customer
 * should see that a surcharge is a percentage, a fixed amount, or both, and
 * that a zero quote means the method is free of fees rather than the order
 * being free.
 */
class PaymentOptionViewTest {

    private static final Instant NOW = Instant.parse("2026-09-17T10:00:00Z");

    @Test
    void quotesTheFeeAndBothOfItsParts() {
        PaymentOptionView view = PaymentOptionView.of(
            method(100L, new BigDecimal("2.5")),
            1200,
            "CNY"
        );

        assertThat(view.handlingFee()).isEqualTo("130");
        assertThat(view.handlingFeeFixed()).isEqualTo("100");
        assertThat(view.handlingFeePercent())
            .isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(view.payableAmount()).isEqualTo("1330");
        assertThat(view.currency()).isEqualTo("CNY");
    }

    @Test
    void leavesTheUnconfiguredPartOfTheFeeNull() {
        PaymentOptionView percent = PaymentOptionView.of(
            method(null, new BigDecimal("2.5")),
            1200,
            "CNY"
        );
        PaymentOptionView fixed = PaymentOptionView.of(
            method(100L, null),
            1200,
            "CNY"
        );

        assertThat(percent.handlingFee()).isEqualTo("30");
        assertThat(percent.handlingFeeFixed()).isNull();
        assertThat(percent.handlingFeePercent())
            .isEqualByComparingTo(new BigDecimal("2.5"));

        assertThat(fixed.handlingFee()).isEqualTo("100");
        assertThat(fixed.handlingFeeFixed()).isEqualTo("100");
        assertThat(fixed.handlingFeePercent()).isNull();
    }

    @Test
    void aFeelessMethodQuotesZeroAndNoParts() {
        PaymentOptionView view = PaymentOptionView.of(
            method(null, null),
            1200,
            "CNY"
        );

        assertThat(view.handlingFee()).isEqualTo("0");
        assertThat(view.handlingFeeFixed()).isNull();
        assertThat(view.handlingFeePercent()).isNull();
        assertThat(view.payableAmount()).isEqualTo("1200");
    }

    private PaymentMethod method(Long fixed, BigDecimal percent) {
        PaymentMethod method = PaymentMethod.create("EPay", "WeChat", NOW);
        method.describe(null, null, fixed, percent, NOW);
        return method;
    }
}
