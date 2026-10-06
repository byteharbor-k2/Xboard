package com.sinx.platform.order.domain;

/** Resolves the inviter's order eligibility mode against the global switch. */
public final class CommissionEligibilityPolicy {

    private CommissionEligibilityPolicy() {
    }

    /** SYSTEM follows the global first-purchase setting; PERIOD and ONETIME override it. */
    public static boolean isFirstPaymentOnly(
        int commissionType,
        boolean systemFirstPaymentOnly
    ) {
        return commissionType == 2
            || commissionType == 0 && systemFirstPaymentOnly;
    }

    /** Whether this placement qualifies, given buyer history excluding pending/cancelled orders. */
    public static boolean isEligible(
        int commissionType,
        boolean systemFirstPaymentOnly,
        boolean hasEligibleHistory
    ) {
        if (commissionType == 1) {
            return true;
        }
        if (commissionType == 0 && !systemFirstPaymentOnly) {
            return true;
        }
        return isFirstPaymentOnly(commissionType, systemFirstPaymentOnly)
            && !hasEligibleHistory;
    }
}
