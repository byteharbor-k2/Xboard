package com.sinx.platform.catalog.domain;

/** Resolves a plan's nullable inherit setting without losing its explicit override. */
public final class TrafficResetPolicyResolver {

    private TrafficResetPolicyResolver() {
    }

    public static TrafficResetPolicy effective(
        ServicePlan plan,
        TrafficResetPolicy globalPolicy
    ) {
        if (plan.getPlanType() == PlanType.TRAFFIC_PACKAGE) {
            return TrafficResetPolicy.NEVER;
        }
        TrafficResetPolicy planPolicy = plan.getResetPolicy();
        return planPolicy == null ? globalPolicy : planPolicy;
    }
}
