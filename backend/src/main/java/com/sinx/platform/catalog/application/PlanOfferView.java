package com.sinx.platform.catalog.application;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.sinx.platform.catalog.domain.BillingPeriod;
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
    List<PlanPriceView> prices,
    boolean newUserOffer
) {
    /** Keeps callers that construct ordinary catalogue offers source-compatible. */
    public PlanOfferView(
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
        this(
            id,
            name,
            description,
            tags,
            planType,
            transferLimitBytes,
            speedLimitMbps,
            resetPolicy,
            renewable,
            resettable,
            purchaseLimitPerUser,
            capacityRemaining,
            prices,
            false
        );
    }

    static PlanOfferView from(ServicePlan plan, Integer capacityRemaining) {
        return from(plan, capacityRemaining, false);
    }

    static PlanOfferView from(
        ServicePlan plan,
        Integer capacityRemaining,
        boolean newUserOffer
    ) {
        TrafficResetPolicy effectivePolicy = plan.getResetPolicy();
        if (effectivePolicy == null) {
            effectivePolicy = plan.getPlanType() == PlanType.TRAFFIC_PACKAGE
                ? TrafficResetPolicy.NEVER
                : TrafficResetPolicy.MONTHLY_FROM_ACTIVATION;
        }
        return from(plan, capacityRemaining, newUserOffer, effectivePolicy);
    }

    static PlanOfferView from(
        ServicePlan plan,
        Integer capacityRemaining,
        boolean newUserOffer,
        TrafficResetPolicy effectivePolicy
    ) {
        return new PlanOfferView(
            plan.getId(),
            plan.getName(),
            plan.getDescription(),
            plan.getTags(),
            plan.getPlanType(),
            Long.toString(plan.getTransferLimitBytes()),
            plan.getSpeedLimitMbps(),
            effectivePolicy,
            plan.isRenewable(),
            plan.isResettable(),
            plan.getPurchaseLimitPerUser(),
            capacityRemaining,
            plan.getPrices().stream()
                .filter(price -> !newUserOffer
                    || price.getBillingPeriod() == BillingPeriod.ONETIME
                )
                .sorted(Comparator.comparing(
                    price -> price.getBillingPeriod().ordinal()
                ))
                .map(PlanPriceView::from)
                .toList(),
            newUserOffer
        );
    }
}
