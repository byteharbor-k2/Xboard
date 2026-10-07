package com.sinx.platform.balance.application;

import java.util.List;

public record BalanceLogPage(
    List<BalanceLogView> items,
    long totalCount,
    int page,
    int limit
) {
    public BalanceLogPage {
        items = List.copyOf(items);
    }
}
