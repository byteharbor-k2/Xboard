package com.sinx.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicyResolver;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.order.domain.OrderStatus;
import com.sinx.platform.order.repository.ServiceOrderRepository;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

@Service
public class CatalogService {

    private static final Set<OrderStatus> COMPLETED_PURCHASES = Set.of(
        OrderStatus.COMPLETED,
        OrderStatus.DISCOUNTED
    );

    private final ServicePlanRepository planRepository;
    private final SubscriptionEntitlementRepository entitlementRepository;
    private final ServiceOrderRepository orderRepository;
    private final PlatformConfigurationService configuration;
    private final Clock clock;

    public CatalogService(
        ServicePlanRepository planRepository,
        SubscriptionEntitlementRepository entitlementRepository,
        ServiceOrderRepository orderRepository,
        PlatformConfigurationService configuration,
        Clock clock
    ) {
        this.planRepository = planRepository;
        this.entitlementRepository = entitlementRepository;
        this.orderRepository = orderRepository;
        this.configuration = configuration;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanOfferView> availableOffers(UUID viewerId) {
        Instant now = Instant.now(clock);
        UUID configuredOfferId = configuration.newUserOfferPlanId().orElse(null);
        boolean eligible = viewerId != null
            && !hasCompletedPurchase(viewerId);

        return planRepository
            .findAllByPublishedTrueAndSellableTrueOrderBySortOrderAscNameAsc()
            .stream()
            .filter(plan -> !plan.getId().equals(configuredOfferId) || eligible)
            .filter(plan -> !plan.getId().equals(configuredOfferId)
                || isValidNewUserOffer(plan))
            .map(plan -> toAvailableOffer(
                plan,
                now,
                plan.getId().equals(configuredOfferId)
            ))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * A single offer for the detail page. Returns empty when the plan is not on
     * sale, so a stale link cannot reveal a withdrawn plan.
     */
    @Transactional(readOnly = true)
    public Optional<PlanOfferView> availableOffer(
        UUID planId,
        UUID viewerId
    ) {
        Instant now = Instant.now(clock);
        UUID configuredOfferId = configuration.newUserOfferPlanId().orElse(null);
        boolean isConfiguredOffer = planId.equals(configuredOfferId);
        if (isConfiguredOffer
                && (viewerId == null || hasCompletedPurchase(viewerId))) {
            return Optional.empty();
        }
        return planRepository.findById(planId)
            .filter(ServicePlan::isPublished)
            .filter(ServicePlan::isSellable)
            .filter(plan -> !isConfiguredOffer || isValidNewUserOffer(plan))
            .map(plan -> toAvailableOffer(plan, now, isConfiguredOffer));
    }

    private PlanOfferView toAvailableOffer(
        ServicePlan plan,
        Instant now,
        boolean newUserOffer
    ) {
        if (plan.getPrices().isEmpty()) {
            return null;
        }
        Integer capacity = plan.getCapacityLimit();
        if (capacity == null) {
            return PlanOfferView.from(
                plan, null, newUserOffer,
                TrafficResetPolicyResolver.effective(
                    plan, configuration.globalTrafficResetPolicy()
                )
            );
        }
        long occupied = entitlementRepository.countActiveForPlan(
            plan.getId(),
            now
        );
        int remaining = Math.max(0, capacity - Math.toIntExact(occupied));
        return remaining == 0
            ? null
            : PlanOfferView.from(
                plan, remaining, newUserOffer,
                TrafficResetPolicyResolver.effective(
                    plan, configuration.globalTrafficResetPolicy()
                )
            );
    }

    private boolean hasCompletedPurchase(UUID userId) {
        return orderRepository.existsByUserIdAndStatusIn(
            userId,
            COMPLETED_PURCHASES
        );
    }

    private boolean isValidNewUserOffer(ServicePlan plan) {
        return plan.getPlanType() == PlanType.TRAFFIC_PACKAGE
            && plan.getPrices().stream().anyMatch(price ->
                price.getBillingPeriod() == BillingPeriod.ONETIME
            );
    }
}
