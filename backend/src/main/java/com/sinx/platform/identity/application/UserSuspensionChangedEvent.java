package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An administrator suspended or restored an account.
 *
 * Sign-in and the subscription endpoint read {@code users.status} directly, so
 * those doors already close (and reopen) on their own. This event exists for
 * the nodes, which hold the account inside their own user list until their
 * next poll: carrying the groups the account reaches nodes through lets the
 * listener push only to the nodes that actually serve it, and firing on a
 * lifted ban too means the same push puts the account back the instant the
 * suspension ends instead of waiting out {@code pull_interval}.
 */
public record UserSuspensionChangedEvent(
    UUID userId,
    List<Long> groupIds,
    boolean suspended,
    Instant at
) {
}
