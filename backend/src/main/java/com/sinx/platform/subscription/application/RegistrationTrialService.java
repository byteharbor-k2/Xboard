package com.sinx.platform.subscription.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sinx.platform.catalog.domain.PlanType;
import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.repository.ServicePlanRepository;
import com.sinx.platform.configuration.application.PlatformConfigurationService;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;
import com.sinx.platform.subscription.repository.SubscriptionEntitlementRepository;

/** Grants the configured trial as part of a successful account registration. */
@Service
public class RegistrationTrialService {

    private final PlatformConfigurationService configuration;
    private final ServicePlanRepository plans;
    private final SubscriptionEntitlementRepository entitlements;
    private final ApplicationEventPublisher events;

    public RegistrationTrialService(
        PlatformConfigurationService configuration,
        ServicePlanRepository plans,
        SubscriptionEntitlementRepository entitlements,
        ApplicationEventPublisher events
    ) {
        this.configuration = configuration;
        this.plans = plans;
        this.entitlements = entitlements;
        this.events = events;
    }

    @Transactional
    public void grantTo(UserAccount user, Instant registeredAt) {
        UUID planId = configuration.trialPlanId().orElse(null);
        if (planId == null) {
            return;
        }
        ServicePlan plan = plans.findById(planId).orElse(null);
        // A stale setting or a plan changed to a package should not prevent an
        // otherwise valid account registration, nor create an invalid trial.
        if (plan == null || plan.getPlanType() != PlanType.SUBSCRIPTION) {
            return;
        }

        Instant expiresAt = registeredAt.plus(
            configuration.trialHours(),
            java.time.temporal.ChronoUnit.HOURS
        );
        SubscriptionEntitlement entitlement = SubscriptionEntitlement.grant(
            UUID.randomUUID(),
            user,
            plan,
            registeredAt,
            expiresAt,
            null,
            registeredAt
        );
        entitlement.markTrial(registeredAt);
        entitlements.save(entitlement);

        Long groupId = entitlement.getEffectiveServerGroupId();
        events.publishEvent(new UserEntitlementChangedEvent(
            user.getId(),
            groupId == null ? List.of() : List.of(groupId),
            registeredAt
        ));
    }
}
