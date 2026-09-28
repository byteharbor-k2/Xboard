package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user's subscription entitlement changed in a way a node can see.
 *
 * What xboard-node holds for an account is not a copy of the row: the user
 * list it serves is built by {@code NodeProtocolService.userPayload}, which
 * puts the plan's speed limit on the wire and decides membership through the
 * entitlement's state - expiry, and whether the traffic is used up. So a
 * purchase that opens or renews the subscription, an administrator's
 * correction to the allowance or expiry, and a traffic reset that lifts an
 * exhausted account back to active all change what the node serves, while a
 * remarks or password edit changes nothing a node reads.
 *
 * Like {@link UserSuspensionChangedEvent}, the event carries the groups the
 * account reaches nodes through so the listener pushes only to the nodes that
 * actually serve it. It lives beside the suspension event rather than beside
 * the entitlement because its publishers sit in identity and order, both of
 * which already depend on this package.
 */
public record UserEntitlementChangedEvent(
    UUID userId,
    List<Long> groupIds,
    Instant at
) {
}
