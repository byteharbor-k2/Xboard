package com.sinx.platform.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;

public record PlanOfferView(
    UUID id,
    String name,
    String description,
    List<String> tags,
    String transferLimitBytes,
    Integer speedLimitMbps,
    Integer deviceLimit,
    TrafficResetPolicy resetPolicy,
    boolean renewable,
    Integer capacityRemaining,
    List<PlanPriceView> prices
) {
    static PlanOfferView from(ServicePlan plan, Integer capacityRemaining) {
        return new PlanOfferView(
            plan.getId(),
            plan.getName(),
            plan.getDescription(),
            plan.getTags(),
            Long.toString(plan.getTransferLimitBytes()),
            plan.getSpeedLimitMbps(),
            plan.getDeviceLimit(),
            plan.getResetPolicy(),
            plan.isRenewable(),
            capacityRemaining,
            plan.getPrices().stream()
                .sorted(Comparator.comparing(
                    price -> price.getBillingPeriod().ordinal()
                ))
                .map(PlanPriceView::from)
                .toList()
        );
    }
}
