package com.sinx.platform.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;

public record ManagedPlanView(
    UUID id,
    String name,
    String description,
    List<String> tags,
    PlanType planType,
    String transferLimitBytes,
    Integer speedLimitMbps,
    Integer deviceLimit,
    TrafficResetPolicy resetPolicy,
    Integer capacityLimit,
    boolean resettable,
    Integer purchaseLimitPerUser,
    Long serverGroupId,
    boolean published,
    boolean sellable,
    boolean renewable,
    int sortOrder,
    long subscriberCount,
    long activeSubscriberCount,
    List<ManagedPlanPriceView> prices
) {
    static ManagedPlanView from(
        ServicePlan plan,
        long subscriberCount,
        long activeSubscriberCount
    ) {
        return new ManagedPlanView(
            plan.getId(),
            plan.getName(),
            plan.getDescription(),
            plan.getTags(),
            plan.getPlanType(),
            Long.toString(plan.getTransferLimitBytes()),
            plan.getSpeedLimitMbps(),
            plan.getDeviceLimit(),
            plan.getResetPolicy(),
            plan.getCapacityLimit(),
            plan.isResettable(),
            plan.getPurchaseLimitPerUser(),
            plan.getServerGroupId(),
            plan.isPublished(),
            plan.isSellable(),
            plan.isRenewable(),
            plan.getSortOrder(),
            subscriberCount,
            activeSubscriberCount,
            plan.getPrices().stream()
                .sorted(Comparator.comparing(
                    price -> price.getBillingPeriod().ordinal()
                ))
                .map(ManagedPlanPriceView::from)
                .toList()
        );
    }
}
