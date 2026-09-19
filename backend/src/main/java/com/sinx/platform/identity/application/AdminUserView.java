package com.sinx.platform.identity.application;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.identity.domain.UserAccount;
import com.sinx.platform.identity.domain.UserStatus;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

/**
 * One account as the admin API reports it.
 *
 * Field names and epoch-second timestamps follow the original panel's admin
 * responses, since these endpoints sit on the Xboard-compatible surface under
 * {@code /api/v2/admin}. Byte counts are strings, as everywhere else on the
 * customer-facing side, because a long can exceed what JSON numbers survive.
 *
 * The subscription figures come from the entitlement rather than the account:
 * that is where the allowance and the counters actually live.
 */
public record AdminUserView(
    @JsonProperty("id") UUID id,
    /** The number the node protocol and the original panel address users by. */
    @JsonProperty("node_user_id") Long nodeUserId,
    @JsonProperty("email") String email,
    @JsonProperty("status") UserStatus status,
    @JsonProperty("banned") boolean banned,
    @JsonProperty("email_verified") boolean emailVerified,
    @JsonProperty("remarks") String remarks,
    @JsonProperty("speed_limit_mbps") Integer speedLimitMbps,
    @JsonProperty("balance") long balance,
    @JsonProperty("plan_id") UUID planId,
    @JsonProperty("plan_name") String planName,
    @JsonProperty("transfer_limit_bytes") String transferLimitBytes,
    @JsonProperty("used_bytes") String usedBytes,
    /** How many devices are on the subscription right now. Observation only. */
    @JsonProperty("online_devices") int onlineDevices,
    @JsonProperty("expires_at") Long expiresAt,
    @JsonProperty("last_login_at") Long lastLoginAt,
    @JsonProperty("created_at") long createdAt
) {
    static AdminUserView of(
        UserAccount account,
        SubscriptionEntitlement entitlement,
        int onlineDevices,
        Instant now
    ) {
        return new AdminUserView(
            account.getId(),
            account.getNodeUserId(),
            account.getEmail(),
            account.getStatus(),
            account.getStatus() == UserStatus.SUSPENDED,
            account.isEmailVerified(),
            account.getRemarks(),
            account.getSpeedLimitMbps(),
            account.getBalanceMinor(),
            entitlement == null ? null : entitlement.getPlanId(),
            entitlement == null ? null : entitlement.getPlanName(),
            entitlement == null
                ? "0"
                : Long.toString(entitlement.getTransferLimitBytes()),
            entitlement == null ? "0" : Long.toString(entitlement.usedBytes()),
            onlineDevices,
            entitlement == null || entitlement.getExpiresAt() == null
                ? null
                : entitlement.getExpiresAt().getEpochSecond(),
            account.getLastLoginAt() == null
                ? null
                : account.getLastLoginAt().getEpochSecond(),
            account.getCreatedAt().getEpochSecond()
        );
    }
}
