package com.sinx.platform.subscription.client;

import java.time.Instant;

/**
 * How much of the account's allowance has been used, and when it runs out.
 *
 * Both things that need it read it from here: the subscription header, which
 * reports raw bytes because that is what clients parse, and the Surge-style
 * information panel, which reports gigabytes because that is what a person
 * reads. They are the same numbers, and keeping them in one place is what stops
 * the two from disagreeing after a change to either.
 *
 * @param expiresAt null when the account does not expire
 */
public record SubscriptionUsage(
    long uploadedBytes,
    long downloadedBytes,
    long transferLimitBytes,
    Instant expiresAt
) {
}
