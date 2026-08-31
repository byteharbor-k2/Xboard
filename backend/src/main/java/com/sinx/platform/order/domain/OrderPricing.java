package com.sinx.platform.order.domain;

/**
 * The deduction pipeline, in the order the original panel applies it:
 * coupon, then the unused value of the plan being replaced, then account
 * balance. Order matters - a coupon discounts the list price, while the
 * surplus and the balance each apply to whatever is still owed after the
 * previous step.
 *
 * Pure arithmetic on minor units so a quote and a real order cannot drift
 * apart: both call this with the same inputs. Every division truncates, which
 * keeps a percentage coupon from ever rounding a cent in the customer's
 * disfavour.
 */
public final class OrderPricing {

    private OrderPricing() {
    }

    /**
     * @param listPriceMinor       the plan's price for the chosen period
     * @param couponDiscountMinor  already resolved against the list price
     * @param surplusMinor         unused value carried over, zero unless this
     *                             is an upgrade
     * @param availableBalanceMinor the account balance that may be spent
     */
    public record Inputs(
        long listPriceMinor,
        long couponDiscountMinor,
        long surplusMinor,
        long availableBalanceMinor
    ) {
    }

    /**
     * @param surplusCredit the part of the surplus that outgrew this order and
     *                      is owed back to the customer rather than applied
     * @param totalAmount   what is still payable
     */
    public record Breakdown(
        long originalAmount,
        long discountAmount,
        long surplusAmount,
        long surplusCredit,
        long balanceAmount,
        long totalAmount
    ) {
        public boolean isFullySettled() {
            return totalAmount == 0;
        }
    }

    public static Breakdown compute(Inputs inputs) {
        long original = Math.max(inputs.listPriceMinor(), 0);
        long discount = clamp(inputs.couponDiscountMinor(), original);
        long owed = original - discount;

        long surplus = Math.max(inputs.surplusMinor(), 0);
        long surplusCredit = 0;
        if (surplus > 0) {
            if (surplus >= owed) {
                surplusCredit = surplus - owed;
                owed = 0;
            } else {
                owed -= surplus;
            }
        }

        long balance = clamp(inputs.availableBalanceMinor(), owed);
        owed -= balance;

        return new Breakdown(
            original,
            discount,
            surplus,
            surplusCredit,
            balance,
            owed
        );
    }

    private static long clamp(long value, long ceiling) {
        return Math.min(Math.max(value, 0), Math.max(ceiling, 0));
    }
}
