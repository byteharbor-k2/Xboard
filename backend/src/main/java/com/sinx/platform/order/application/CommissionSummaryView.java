package com.sinx.platform.order.application;

/** Money fields use minor-unit strings to preserve 64-bit values over GraphQL. */
public record CommissionSummaryView(
    int effectiveRatePercent,
    int commissionType,
    boolean firstPaymentOnly,
    String pendingMinor,
    String confirmedPendingMinor,
    String earnedMinor,
    boolean autoConfirmEnabled,
    boolean distributionEnabled,
    int distributionL1,
    int distributionL2,
    int distributionL3,
    String payoutDestination
) {
}
