package com.sinx.platform.order.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

@Validated
@ConfigurationProperties(prefix = "sinx.order")
public record OrderProperties(
    /**
     * How long an order may wait for payment before it is called off and its
     * balance and coupon are handed back. Two hours, as in the original.
     */
    @NotNull Duration pendingExpiry,

    /** How often the sweep looks for orders that have waited too long. */
    @NotNull Duration sweepInterval
) {
}
