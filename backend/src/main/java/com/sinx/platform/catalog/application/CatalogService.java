package com.sinx.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

@Service
public class CatalogService {

    private final ServicePlanRepository planRepository;
    private final SubscriptionEntitlementRepository entitlementRepository;
    private final Clock clock;

    public CatalogService(
        ServicePlanRepository planRepository,
        SubscriptionEntitlementRepository entitlementRepository,
        Clock clock
    ) {
        this.planRepository = planRepository;
        this.entitlementRepository = entitlementRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanOfferView> availableOffers() {
        Instant now = Instant.now(clock);
        return planRepository
            .findAllByPublishedTrueAndSellableTrueOrderBySortOrderAscNameAsc()
            .stream()
            .map(plan -> toAvailableOffer(plan, now))
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private PlanOfferView toAvailableOffer(ServicePlan plan, Instant now) {
        if (plan.getPrices().isEmpty()) {
            return null;
        }
        Integer capacity = plan.getCapacityLimit();
        if (capacity == null) {
            return PlanOfferView.from(plan, null);
        }
        long occupied = entitlementRepository.countActiveForPlan(
            plan.getId(),
            now
        );
        int remaining = Math.max(0, capacity - Math.toIntExact(occupied));
        return remaining == 0 ? null : PlanOfferView.from(plan, remaining);
    }
}
