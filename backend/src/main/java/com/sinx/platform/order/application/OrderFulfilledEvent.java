package com.sinx.platform.order.application;

import java.time.Instant;
import java.util.UUID;

/**
 * An order finished being provisioned: the subscription was opened, renewed
 * or upgraded and the order closed.
 *
 * This is the customer-facing counterpart of the settlement itself. Nothing
 * here is needed to run the service - it exists so the notification module
 * can send the fulfilment mail with everything the template needs, resolved
 * while the order is still in hand rather than re-derived from the trade
 * number afterwards. The period label and the expiry are display data; the
 * subscription token deliberately does not travel with the event, because
 * the fulfilment mail must never carry the credential.
 */
public record OrderFulfilledEvent(
    String tradeNo,
    UUID userId,
    String email,
    String displayName,
    String planName,
    String periodLabel,
    Instant expiresAt,
    Instant at
) {
}
