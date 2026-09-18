package com.sinx.platform.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;

public record PlanOfferView(
    UUID id,
    String name,
    String description,
    List<String> tags,
    PlanType planType,
    String transferLimitBytes,
    Integer speedLimitMbps,
    TrafficResetPolicy resetPolicy,
    boolean renewable,
    boolean resettable,
    Integer purchaseLimitPerUser,
    Integer capacityRemaining,
    List<PlanPriceView> prices
) {
    static PlanOfferView from(ServicePlan plan, Integer capacityRemaining) {
        return new PlanOfferView(
            plan.getId(),
            plan.getName(),
            plan.getDescription(),
            plan.getTags(),
            plan.getPlanType(),
            Long.toString(plan.getTransferLimitBytes()),
            plan.getSpeedLimitMbps(),
            plan.getResetPolicy(),
            plan.isRenewable(),
            plan.isResettable(),
            plan.getPurchaseLimitPerUser(),
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
