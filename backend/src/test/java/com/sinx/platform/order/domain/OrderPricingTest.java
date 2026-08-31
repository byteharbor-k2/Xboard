package com.sinx.platform.order.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderPricingTest {

    @Test
    void chargesTheListPriceWhenNothingOffsetsIt() {
        OrderPricing.Breakdown breakdown = compute(3500, 0, 0, 0);

        assertThat(breakdown.originalAmount()).isEqualTo(3500);
        assertThat(breakdown.totalAmount()).isEqualTo(3500);
        assertThat(breakdown.isFullySettled()).isFalse();
    }

    @Test
    void neverLetsACouponDiscountMoreThanThePrice() {
        OrderPricing.Breakdown breakdown = compute(3500, 9900, 0, 0);

        assertThat(breakdown.discountAmount()).isEqualTo(3500);
        assertThat(breakdown.totalAmount()).isZero();
        assertThat(breakdown.isFullySettled()).isTrue();
    }

    @Test
    void appliesTheSurplusToWhatIsStillOwedAfterTheCoupon() {
        // 35.00 list, 5.00 coupon, 20.00 carried over -> 10.00 payable.
        OrderPricing.Breakdown breakdown = compute(3500, 500, 2000, 0);

        assertThat(breakdown.discountAmount()).isEqualTo(500);
        assertThat(breakdown.surplusAmount()).isEqualTo(2000);
        assertThat(breakdown.surplusCredit()).isZero();
        assertThat(breakdown.totalAmount()).isEqualTo(1000);
    }

    @Test
    void keepsTheSurplusThatOutgrowsTheOrderAsCreditRatherThanANegativeTotal() {
        OrderPricing.Breakdown breakdown = compute(3500, 0, 5000, 0);

        assertThat(breakdown.surplusAmount()).isEqualTo(5000);
        assertThat(breakdown.surplusCredit()).isEqualTo(1500);
        assertThat(breakdown.totalAmount()).isZero();
    }

    @Test
    void spendsTheBalanceLastAndOnlyUpToWhatIsOwed() {
        OrderPricing.Breakdown breakdown = compute(3500, 0, 0, 10_000);

        assertThat(breakdown.balanceAmount()).isEqualTo(3500);
        assertThat(breakdown.totalAmount()).isZero();
    }

    @Test
    void takesOnlyPartOfTheBalanceWhenItDoesNotCoverTheOrder() {
        OrderPricing.Breakdown breakdown = compute(3500, 0, 0, 1200);

        assertThat(breakdown.balanceAmount()).isEqualTo(1200);
        assertThat(breakdown.totalAmount()).isEqualTo(2300);
    }

    @Test
    void appliesCouponThenSurplusThenBalance() {
        // The order matters: a balance large enough to cover the list price
        // must still only absorb what the earlier deductions left behind.
        OrderPricing.Breakdown breakdown = compute(3500, 500, 1000, 5000);

        assertThat(breakdown.discountAmount()).isEqualTo(500);
        assertThat(breakdown.surplusAmount()).isEqualTo(1000);
        assertThat(breakdown.balanceAmount()).isEqualTo(2000);
        assertThat(breakdown.totalAmount()).isZero();
    }

    @Test
    void leavesTheBalanceAloneOnceTheSurplusHasSettledTheOrder() {
        OrderPricing.Breakdown breakdown = compute(3500, 0, 4000, 5000);

        assertThat(breakdown.balanceAmount()).isZero();
        assertThat(breakdown.surplusCredit()).isEqualTo(500);
        assertThat(breakdown.totalAmount()).isZero();
    }

    private OrderPricing.Breakdown compute(
        long listPrice,
        long couponDiscount,
        long surplus,
        long balance
    ) {
        return OrderPricing.compute(new OrderPricing.Inputs(
            listPrice,
            couponDiscount,
            surplus,
            balance
        ));
    }
}
