package com.sinx.platform.subscription.domain;

/** Distinguishes a real cycle rollover from counter resets that keep the same cycle. */
public enum TrafficResetKind {
    AUTOMATIC,
    MANUAL,
    PAID
}
