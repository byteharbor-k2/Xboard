package com.sinx.platform.balance.application;

/** The viewer's live ordinary-balance total and the ledger reconciliation anchors. */
public record BalanceSummaryView(
    String balanceMinor,
    String openingBalanceMinor,
    String totalCreditsMinor,
    String totalDebitsMinor,
    String recordedSince
) {
}
