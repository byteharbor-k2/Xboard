package com.sinx.platform.payment.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.payment.domain.PaymentMethod;

/**
 * One configured payment method as the admin API reports it.
 *
 * The field names are the original panel's, since these endpoints sit on the
 * Xboard-compatible surface under {@code /api/v2/admin} - {@code payment} is the
 * gateway code, {@code enable} the switch, {@code sort} the ordering. The
 * credentials are included so an existing configuration can be edited without
 * retyping the merchant key; this is the only surface that returns them, and it
 * requires an administrator.
 */
public record PaymentMethodAdminView(
    @JsonProperty("id") UUID id,
    @JsonProperty("uuid") String uuid,
    @JsonProperty("payment") String gateway,
    @JsonProperty("name") String name,
    @JsonProperty("icon") String icon,
    @JsonProperty("notify_domain") String notifyDomain,
    /** Where the gateway should send the payment result, computed server-side. */
    @JsonProperty("notify_url") String notifyUrl,
    /**
     * True when that address is one a remote gateway cannot reach - a local
     * name or a private network - so a payment would be taken and its callback
     * never arrive. A warning for the administrator, not a refusal to save.
     */
    @JsonProperty("notify_unreachable") boolean notifyUnreachable,
    @JsonProperty("handling_fee_fixed") Long handlingFeeFixed,
    @JsonProperty("handling_fee_percent") BigDecimal handlingFeePercent,
    @JsonProperty("enable") boolean enable,
    @JsonProperty("sort") int sort,
    @JsonProperty("config") Map<String, String> config,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt
) {
    static PaymentMethodAdminView from(
        PaymentMethod method,
        String notifyUrl,
        Map<String, String> config
    ) {
        return new PaymentMethodAdminView(
            method.getId(),
            method.getUuid(),
            method.getGateway(),
            method.getName(),
            method.getIcon(),
            method.getNotifyDomain(),
            notifyUrl,
            !NotifyUrlReachability.reachableByRemoteGateway(notifyUrl),
            method.getHandlingFeeFixed(),
            method.getHandlingFeePercent(),
            method.isEnabled(),
            method.getSortOrder(),
            config,
            method.getCreatedAt().getEpochSecond(),
            method.getUpdatedAt().getEpochSecond()
        );
    }
}
