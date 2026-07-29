package com.sinx.platform.subscription.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.subscription.domain.EntitlementState;
import com.sinx.platform.subscription.domain.SubscriptionEntitlement;

public record SubscriptionEntitlementView(
    UUID id,
    UUID planId,
    String planName,
    EntitlementState state,
    String transferLimitBytes,
    String uploadedBytes,
    String downloadedBytes,
    String usedBytes,
    String remainingBytes,
    double usagePercent,
    Integer speedLimitMbps,
    Integer deviceLimit,
    TrafficResetPolicy resetPolicy,
    Instant startsAt,
    Instant expiresAt,
    Instant nextResetAt
) {
    static SubscriptionEntitlementView from(
        SubscriptionEntitlement entitlement,
        Instant now
    ) {
        long used = entitlement.usedBytes();
        double percent = BigDecimal.valueOf(used)
            .multiply(BigDecimal.valueOf(100))
            .divide(
                BigDecimal.valueOf(entitlement.getTransferLimitBytes()),
                2,
                RoundingMode.HALF_UP
            )
            .min(BigDecimal.valueOf(100))
            .doubleValue();
        return new SubscriptionEntitlementView(
            entitlement.getId(),
            entitlement.getPlanId(),
            entitlement.getPlanName(),
            entitlement.stateAt(now),
            Long.toString(entitlement.getTransferLimitBytes()),
            Long.toString(entitlement.getUploadedBytes()),
            Long.toString(entitlement.getDownloadedBytes()),
            Long.toString(used),
            Long.toString(entitlement.remainingBytes()),
            percent,
            entitlement.getSpeedLimitMbps(),
            entitlement.getDeviceLimit(),
            entitlement.getResetPolicy(),
            entitlement.getStartsAt(),
            entitlement.getExpiresAt(),
            entitlement.getNextResetAt()
        );
    }
}
