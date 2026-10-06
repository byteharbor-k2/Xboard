package com.sinx.platform.catalog.application;

import java.util.UUID;

/** Signals that existing snapshots for this plan may need a new boundary. */
public record PlanTrafficResetPolicyChangedEvent(UUID planId) {
}
