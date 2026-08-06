package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import com.sinx.platform.catalog.domain.ServicePlan;
import com.sinx.platform.catalog.domain.TrafficResetPolicy;
import com.sinx.platform.identity.domain.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "subscription_entitlements")
public class SubscriptionEntitlement {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ServicePlan plan;

    @Column(name = "plan_name", length = 120, nullable = false)
    private String planName;

    @Column(name = "transfer_limit_bytes", nullable = false)
    private long transferLimitBytes;

    @Column(name = "uploaded_bytes", nullable = false)
    private long uploadedBytes;

    @Column(name = "downloaded_bytes", nullable = false)
    private long downloadedBytes;

    @Column(name = "speed_limit_mbps")
    private Integer speedLimitMbps;

    @Column(name = "device_limit")
    private Integer deviceLimit;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_policy", length = 32, nullable = false)
    private TrafficResetPolicy resetPolicy;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "next_reset_at")
    private Instant nextResetAt;

    @Column(name = "canceled_at")
    private Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected SubscriptionEntitlement() {
    }

    public static SubscriptionEntitlement grant(
        UUID id,
        UserAccount user,
        ServicePlan plan,
        Instant startsAt,
        Instant expiresAt,
        Instant nextResetAt,
        Instant now
    ) {
        SubscriptionEntitlement entitlement =
            new SubscriptionEntitlement();
        entitlement.id = id;
        entitlement.user = user;
        entitlement.plan = plan;
        entitlement.planName = plan.getName();
        entitlement.transferLimitBytes = plan.getTransferLimitBytes();
        entitlement.speedLimitMbps = plan.getSpeedLimitMbps();
        entitlement.deviceLimit = plan.getDeviceLimit();
        entitlement.resetPolicy = plan.getResetPolicy();
        entitlement.startsAt = startsAt;
        entitlement.expiresAt = expiresAt;
        entitlement.nextResetAt = nextResetAt;
        entitlement.createdAt = now;
        entitlement.updatedAt = now;
        return entitlement;
    }

    public void recordUsage(long uploadedBytes, long downloadedBytes, Instant now) {
        if (uploadedBytes < 0 || downloadedBytes < 0) {
            throw new IllegalArgumentException("Traffic usage cannot be negative");
        }
        this.uploadedBytes = uploadedBytes;
        this.downloadedBytes = downloadedBytes;
        updatedAt = now;
    }

    public void addUsage(long uploadedDelta, long downloadedDelta, Instant now) {
        if (uploadedDelta < 0 || downloadedDelta < 0) {
            throw new IllegalArgumentException("Traffic usage delta cannot be negative");
        }
        uploadedBytes = saturatedAdd(uploadedBytes, uploadedDelta);
        downloadedBytes = saturatedAdd(downloadedBytes, downloadedDelta);
        updatedAt = now;
    }

    public EntitlementState stateAt(Instant now) {
        if (canceledAt != null) {
            return EntitlementState.CANCELED;
        }
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return EntitlementState.EXPIRED;
        }
        if (usedBytes() >= transferLimitBytes) {
            return EntitlementState.EXHAUSTED;
        }
        return EntitlementState.ACTIVE;
    }

    public long usedBytes() {
        return saturatedAdd(uploadedBytes, downloadedBytes);
    }

    public long remainingBytes() {
        return Math.max(0, transferLimitBytes - usedBytes());
    }

    public UUID getId() {
        return id;
    }

    public UUID getPlanId() {
        return plan.getId();
    }

    public String getPlanName() {
        return planName;
    }

    public long getTransferLimitBytes() {
        return transferLimitBytes;
    }

    public long getUploadedBytes() {
        return uploadedBytes;
    }

    public long getDownloadedBytes() {
        return downloadedBytes;
    }

    public Integer getSpeedLimitMbps() {
        return speedLimitMbps;
    }

    public Integer getDeviceLimit() {
        return deviceLimit;
    }

    public TrafficResetPolicy getResetPolicy() {
        return resetPolicy;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getNextResetAt() {
        return nextResetAt;
    }

    public UserAccount getUser() {
        return user;
    }

    /**
     * An administrator may explicitly override a user's node group. Otherwise
     * the entitlement follows the group currently assigned to its plan.
     */
    public Long getEffectiveServerGroupId() {
        Long explicitGroupId = user.getServerGroupId();
        return explicitGroupId != null ? explicitGroupId : plan.getServerGroupId();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
