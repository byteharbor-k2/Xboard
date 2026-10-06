package com.sinx.platform.order.application;

import java.util.List;

public record CommissionLogPage(
    List<CommissionLogView> items,
    long totalCount,
    int page,
    int limit
) {
    public CommissionLogPage {
        items = List.copyOf(items);
    }
}
