package com.sinx.platform.subscription.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.sinx.platform.catalog.application.PlanTrafficResetPolicyChangedEvent;
import com.sinx.platform.configuration.application.TrafficResetPolicyChangedEvent;
import com.sinx.platform.identity.application.UserEntitlementChangedEvent;

/** Applies committed policy changes to paid entitlement snapshots, without clearing usage. */
@Component
public class TrafficResetPolicyReconciliationListener {

    private final TrafficResetService trafficResets;

    public TrafficResetPolicyReconciliationListener(
        TrafficResetService trafficResets
    ) {
        this.trafficResets = trafficResets;
    }

    @TransactionalEventListener
    public void globalPolicyChanged(TrafficResetPolicyChangedEvent event) {
        trafficResets.synchronizeInheritedPolicies();
    }

    @TransactionalEventListener
    public void planPolicyChanged(PlanTrafficResetPolicyChangedEvent event) {
        trafficResets.synchronizePoliciesForPlan(event.planId());
    }

    /** Closes the grant-versus-settings-commit race after either transaction commits. */
    @TransactionalEventListener
    public void entitlementChanged(UserEntitlementChangedEvent event) {
        trafficResets.synchronizePolicyForUser(event.userId());
    }
}
