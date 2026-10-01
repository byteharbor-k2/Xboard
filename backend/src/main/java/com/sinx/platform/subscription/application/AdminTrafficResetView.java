package com.sinx.platform.subscription.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.sinx.platform.subscription.domain.TrafficResetRecord;

/**
 * One reset as the admin surface's ledger shows it.
 *
 * Epoch seconds and snake_case, like the orders list: the original panel's
 * admin shape is what every client of this surface renders around. The
 * account's identity - the address it flies under and the id its node list
 * carries - is joined from the account, not carried, because the ledger row
 * is written once and the account may rename itself as long as it lives.
 */
public record AdminTrafficResetView(
    UUID user_id,
    String email,
    Long node_user_id,
    UUID entitlement_id,
    long reset_at,
    long uploaded_bytes_before,
    long downloaded_bytes_before
) {
    static AdminTrafficResetView from(
        TrafficResetRecord record,
        Map<UUID, TrafficResetService.AccountIdentity> identities
    ) {
        TrafficResetService.AccountIdentity identity =
            identities.get(record.getUserId());
        Instant resetAt = record.getResetAt();
        return new AdminTrafficResetView(
            record.getUserId(),
            identity == null ? null : identity.email(),
            identity == null ? null : identity.nodeUserId(),
            record.getEntitlementId(),
            resetAt.getEpochSecond(),
            record.getUploadedBytesBefore(),
            record.getDownloadedBytesBefore()
        );
    }
}
