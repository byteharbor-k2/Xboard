package com.sinx.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.BillingPeriod;
import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.shared.web.ApiProblemException;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

@Service
public class PlanManagementService {

    private static final long MAX_TRANSFER_BYTES =
        1024L * 1024L * 1024L * 1024L * 1024L;

    private final ServicePlanRepository planRepository;
    private final SubscriptionEntitlementRepository entitlementRepository;
    private final Clock clock;

    public PlanManagementService(
        ServicePlanRepository planRepository,
        SubscriptionEntitlementRepository entitlementRepository,
        Clock clock
    ) {
        this.planRepository = planRepository;
        this.entitlementRepository = entitlementRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ManagedPlanView> list() {
        Instant now = Instant.now(clock);
        return planRepository.findAll(
            Sort.by("sortOrder").ascending().and(Sort.by("name").ascending())
        ).stream().map(plan -> ManagedPlanView.from(
            plan,
            entitlementRepository.countForPlan(plan.getId()),
            entitlementRepository.countActiveForPlan(plan.getId(), now)
        )).toList();
    }

    @Transactional
    public ManagedPlanView create(PlanDraft draft) {
        ValidatedDraft values = validate(draft);
        Instant now = Instant.now(clock);
        ServicePlan plan = ServicePlan.create(
            UUID.randomUUID(),
            values.name(),
            values.description(),
            values.planType(),
            values.transferLimitBytes(),
            values.speedLimitMbps(),
            values.deviceLimit(),
            values.resetPolicy(),
            values.capacityLimit(),
            values.resettable(),
            values.purchaseLimitPerUser(),
            values.published(),
            values.sellable(),
            values.renewable(),
            values.sortOrder(),
            values.tags(),
            now
        );
        syncPrices(plan, values.prices());
        ServicePlan saved = planRepository.save(plan);
        return ManagedPlanView.from(saved, 0, 0);
    }

    @Transactional
    public ManagedPlanView update(UUID id, PlanDraft draft) {
        ValidatedDraft values = validate(draft);
        ServicePlan plan = find(id);
        plan.update(
            values.name(),
            values.description(),
            values.planType(),
            values.transferLimitBytes(),
            values.speedLimitMbps(),
            values.deviceLimit(),
            values.resetPolicy(),
            values.capacityLimit(),
            values.resettable(),
            values.purchaseLimitPerUser(),
            values.published(),
            values.sellable(),
            values.renewable(),
            values.sortOrder(),
            values.tags(),
            Instant.now(clock)
        );
        syncPrices(plan, values.prices());
        return ManagedPlanView.from(
            plan,
            entitlementRepository.countForPlan(id),
            entitlementRepository.countActiveForPlan(id, Instant.now(clock))
        );
    }

    @Transactional
    public void delete(UUID id) {
        ServicePlan plan = find(id);
        if (entitlementRepository.countForPlan(id) > 0) {
            throw new ApiProblemException(
                HttpStatus.CONFLICT,
                "PLAN_IN_USE",
                "A plan with subscription history cannot be deleted"
            );
        }
        planRepository.delete(plan);
    }

    private ServicePlan find(UUID id) {
        return planRepository.findById(id).orElseThrow(
            () -> new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "PLAN_NOT_FOUND",
                "The requested plan does not exist"
            )
        );
    }

    private void syncPrices(
        ServicePlan plan,
        List<PriceDraft> prices
    ) {
        Set<BillingPeriod> periods = new LinkedHashSet<>();
        for (PriceDraft price : prices) {
            if (!periods.add(price.period())) {
                throw invalid("Each billing period can only be configured once");
            }
            if (price.amountMinor() <= 0) {
                throw invalid("Plan prices must be greater than zero");
            }
            String currency = normalizeCurrency(price.currency());
            plan.addPrice(price.period(), price.amountMinor(), currency);
        }
        plan.retainPrices(periods);
    }

    private ValidatedDraft validate(PlanDraft draft) {
        String name = draft.name() == null ? "" : draft.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw invalid("Plan name must contain between 1 and 120 characters");
        }
        String description = draft.description() == null
            ? ""
            : draft.description().trim();
        if (description.length() > 8000) {
            throw invalid("Plan description is too long");
        }
        long transfer;
        try {
            transfer = Long.parseLong(draft.transferLimitBytes());
        } catch (RuntimeException exception) {
            throw invalid("Transfer limit must be a valid byte count");
        }
        if (transfer <= 0 || transfer > MAX_TRANSFER_BYTES) {
            throw invalid("Transfer limit is outside the supported range");
        }
        positiveOrNull(draft.speedLimitMbps(), "Speed limit");
        positiveOrNull(draft.deviceLimit(), "Device limit");
        positiveOrNull(draft.capacityLimit(), "Capacity limit");
        positiveOrNull(
            draft.purchaseLimitPerUser(),
            "Per-user purchase limit"
        );
        if (draft.planType() == null) {
            throw invalid("Plan type is required");
        }
        if (draft.resetPolicy() == null) {
            throw invalid("Traffic reset policy is required");
        }
        List<String> tags = normalizeTags(draft.tags());
        List<PriceDraft> prices = draft.prices() == null
            ? List.of()
            : List.copyOf(draft.prices());
        boolean trafficPackage = draft.planType() == PlanType.TRAFFIC_PACKAGE;
        TrafficResetPolicy resetPolicy = trafficPackage
            ? TrafficResetPolicy.NEVER
            : draft.resetPolicy();
        boolean resettable = draft.resettable();
        Integer purchaseLimit = trafficPackage
            ? draft.purchaseLimitPerUser()
            : null;
        boolean renewable = trafficPackage ? false : draft.renewable();
        validatePricePeriods(draft.planType(), resettable, prices);
        return new ValidatedDraft(
            name,
            description,
            draft.planType(),
            transfer,
            draft.speedLimitMbps(),
            draft.deviceLimit(),
            resetPolicy,
            draft.capacityLimit(),
            resettable,
            purchaseLimit,
            draft.published(),
            draft.sellable(),
            renewable,
            draft.sortOrder(),
            tags,
            prices
        );
    }

    private void validatePricePeriods(
        PlanType planType,
        boolean resettable,
        List<PriceDraft> prices
    ) {
        if (prices.isEmpty()) {
            throw invalid("At least one price is required");
        }
        Set<BillingPeriod> periods = prices.stream()
            .map(PriceDraft::period)
            .collect(java.util.stream.Collectors.toSet());
        if (periods.contains(null)) {
            throw invalid("Billing period is required");
        }
        if (planType == PlanType.SUBSCRIPTION) {
            if (periods.contains(BillingPeriod.ONETIME)) {
                throw invalid(
                    "Subscription plans only support recurring billing periods"
                );
            }
            if (periods.stream().noneMatch(
                period -> period != BillingPeriod.RESET_TRAFFIC
            )) {
                throw invalid(
                    "Subscription plans require at least one recurring price"
                );
            }
            validateResetPrice(resettable, periods);
            return;
        }
        if (!periods.contains(BillingPeriod.ONETIME)) {
            throw invalid("Traffic packages require a one-time price");
        }
        if (periods.stream().anyMatch(
            period -> period != BillingPeriod.ONETIME
                && period != BillingPeriod.RESET_TRAFFIC
        )) {
            throw invalid(
                "Traffic packages only support package and reset prices"
            );
        }
        validateResetPrice(resettable, periods);
    }

    private void validateResetPrice(
        boolean resettable,
        Set<BillingPeriod> periods
    ) {
        if (resettable != periods.contains(BillingPeriod.RESET_TRAFFIC)) {
            throw invalid(
                resettable
                    ? "Plans with traffic reset enabled require a reset price"
                    : "A reset price requires traffic reset to be enabled"
            );
        }
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String value = tag.trim();
            if (value.length() > 48) {
                throw invalid("Plan tags cannot exceed 48 characters");
            }
            normalized.add(value);
        }
        if (normalized.size() > 12) {
            throw invalid("A plan can contain at most 12 tags");
        }
        return List.copyOf(normalized);
    }

    private String normalizeCurrency(String currency) {
        String normalized = currency == null
            ? ""
            : currency.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw invalid("Currency must be a three-letter ISO code");
        }
        return normalized;
    }

    private void positiveOrNull(Integer value, String field) {
        if (value != null && value <= 0) {
            throw invalid(field + " must be greater than zero");
        }
    }

    private ApiProblemException invalid(String detail) {
        return new ApiProblemException(
            HttpStatus.BAD_REQUEST,
            "PLAN_DEFINITION_INVALID",
            detail
        );
    }

    public record PlanDraft(
        String name,
        String description,
        PlanType planType,
        String transferLimitBytes,
        Integer speedLimitMbps,
        Integer deviceLimit,
        TrafficResetPolicy resetPolicy,
        Integer capacityLimit,
        boolean resettable,
        Integer purchaseLimitPerUser,
        boolean published,
        boolean sellable,
        boolean renewable,
        int sortOrder,
        List<String> tags,
        List<PriceDraft> prices
    ) {
    }

    public record PriceDraft(
        BillingPeriod period,
        long amountMinor,
        String currency
    ) {
    }

    private record ValidatedDraft(
        String name,
        String description,
        PlanType planType,
        long transferLimitBytes,
        Integer speedLimitMbps,
        Integer deviceLimit,
        TrafficResetPolicy resetPolicy,
        Integer capacityLimit,
        boolean resettable,
        Integer purchaseLimitPerUser,
        boolean published,
        boolean sellable,
        boolean renewable,
        int sortOrder,
        List<String> tags,
        List<PriceDraft> prices
    ) {
    }
}
