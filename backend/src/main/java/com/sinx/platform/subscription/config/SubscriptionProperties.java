package com.sinx.platform.subscription.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "sinx.subscription")
public record SubscriptionProperties(
    /**
     * Where subscription links point when an administrator has not set a
     * subscription address of their own. It has to be an address the customer's
     * client can reach, not one that only resolves inside this network, and it
     * is the seam a deployment that serves subscriptions from a hostname of
     * their own - separate from the site, or one that is not behind the same
     * CDN - configures it through.
     */
    @NotBlank String publicBaseUrl
) {
}
