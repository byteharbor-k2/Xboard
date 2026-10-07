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

    @Column(name = "is_trial", nullable = false)
    private boolean trial;

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
        TrafficResetPolicy resetPolicy = plan.getResetPolicy();
        if (resetPolicy == null) {
            resetPolicy = plan.getPlanType()
                    == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
                ? TrafficResetPolicy.NEVER
                : TrafficResetPolicy.MONTHLY_FROM_ACTIVATION;
        }
        return grant(
            id, user, plan, startsAt, expiresAt, nextResetAt, resetPolicy, now
        );
    }

    public static SubscriptionEntitlement grant(
        UUID id,
        UserAccount user,
        ServicePlan plan,
        Instant startsAt,
        Instant expiresAt,
        Instant nextResetAt,
        TrafficResetPolicy effectiveResetPolicy,
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
        entitlement.resetPolicy = plan.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
            ? TrafficResetPolicy.NEVER
            : effectiveResetPolicy;
        entitlement.startsAt = startsAt;
        entitlement.expiresAt = expiresAt;
        // Seed the traffic cycle the plan calls for; a caller that knows
        // better may hand a boundary of its own.
        entitlement.nextResetAt = nextResetAt != null
            ? nextResetAt
            : MonthlyResetSchedule.initialBoundary(
                entitlement.resetPolicy, startsAt
            );
        entitlement.createdAt = now;
        entitlement.updatedAt = now;
        return entitlement;
    }

    /**
     * Puts a periodic plan on this entitlement.
     *
     * The caller decides where the coverage ends, because that is where the
     * original panel's three cases differ: a renewal stacks onto whatever time
     * is left, an upgrade forfeits it (its value was already handed back
     * through the surplus deduction), and a first periodic purchase starts from
     * now.
     *
     * @param resetTraffic whether the counters start over. True when the
     *     customer was not on a periodic plan before - a first purchase, or a
     *     move off a non-expiring package - and false for renewal and upgrade,
     *     matching the original's {@code buyByPeriod}.
     */
    public void provisionPeriodic(
        ServicePlan plan,
        Instant expiresAt,
        boolean resetTraffic,
        Instant now
    ) {
        provisionPeriodic(
            plan,
            expiresAt,
            resetTraffic,
            effectivePlanPolicy(plan),
            now
        );
    }

    public void provisionPeriodic(
        ServicePlan plan,
        Instant expiresAt,
        boolean resetTraffic,
        TrafficResetPolicy effectiveResetPolicy,
        Instant now
    ) {
        if (expiresAt == null) {
            throw new IllegalArgumentException(
                "A periodic plan must end at some point"
            );
        }
        applyPlan(plan, expiresAt, effectiveResetPolicy, now);
        if (resetTraffic) {
            // A fresh paid activation (or conversion from a non-expiring
            // package) starts its own cycle even when its effective policy
            // happens to match the previous entitlement's policy.
            startsAt = now;
            nextResetAt = MonthlyResetSchedule.initialBoundary(resetPolicy, now);
            clearCounters(now);
        }
    }

    /**
     * Puts a non-expiring traffic package on this entitlement.
     *
     * Counterpart of the original's {@code buyByOneTime}: the package replaces
     * whatever was running, including its expiry, and the counters start over
     * because the customer is buying a fresh bucket of traffic.
     */
    public void provisionPackage(ServicePlan plan, Instant now) {
        applyPlan(plan, null, TrafficResetPolicy.NEVER, now);
        clearCounters(now);
    }

    /** Marks an entitlement granted without a paid order as a registration trial. */
    public void markTrial(Instant now) {
        trial = true;
        // A trial is bounded by its expiry, not by the plan's paid traffic
        // cycle. Its plan policy is retained for a later purchase conversion.
        resetPolicy = TrafficResetPolicy.NEVER;
        nextResetAt = null;
        updatedAt = now;
    }

    /** A completed purchase turns the account's current entitlement into paid service. */
    public void markPurchased(Instant now) {
        if (trial) {
            startsAt = now;
            nextResetAt = MonthlyResetSchedule.initialBoundary(resetPolicy, now);
        }
        trial = false;
        updatedAt = now;
    }

    /** Zeroes the counters, leaving the plan and its expiry untouched. */
    public void resetTraffic(Instant now) {
        clearCounters(now);
    }

    /**
     * Zeroes the counters inside a running monthly cycle.
     *
     * The reset itself is the same clearing a manual one performs; what lifts
     * it out of the ordinary is the boundary handed in: the cycle carries on
     * from instantly after the passed one, so the customer keeps a monthly
     * rhythm and a reset that lands late cannot shorten the one that follows.
     */
    public void resetTrafficInCycle(Instant now, Instant nextBoundary) {
        clearCounters(now);
        nextResetAt = trial ? null : nextBoundary;
    }

    /** Paid self-service reset clears usage without moving the scheduled cycle boundary. */
    public void resetTrafficWithoutReanchoring(Instant now, Instant nextBoundary) {
        clearCounters(now);
        nextResetAt = nextBoundary;
    }

    /** Changes the effective policy without erasing usage or catching up old cycles. */
    public boolean synchronizeResetPolicy(
        TrafficResetPolicy effectivePolicy,
        Instant now
    ) {
        if (trial || plan.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
            || resetPolicy == effectivePolicy) {
            return false;
        }
        resetPolicy = effectivePolicy;
        boolean active = canceledAt == null
            && (expiresAt == null || expiresAt.isAfter(now));
        nextResetAt = active
            ? MonthlyResetSchedule.nextBoundary(effectivePolicy, now, startsAt)
            : null;
        updatedAt = now;
        return true;
    }

    /**
     * Seeds the entitlement's traffic cycle from its activation instant, for
     * entitlements granted before a cycle existed. Resets nothing and
     * records nothing; the first due pass of the cycle walks the anchor
     * forward to today's boundary, and the rhythm starts there.
     */
    public void seedPeriodicBoundary() {
        nextResetAt = trial
            ? null
            : MonthlyResetSchedule.initialBoundary(resetPolicy, startsAt);
    }

    /**
     * Applies an administrator's corrections to the allowance, the expiry and
     * optionally the plan behind them.
     *
     * Usage counters are deliberately left alone. An operator adjusting an
     * allowance is correcting what the customer is entitled to, not erasing
     * what they have already used - resetting traffic is a separate, explicit
     * action.
     *
     * Passing a null plan keeps the current one, which is the common case: most
     * corrections are to the numbers, not to which plan they came from.
     */
    public void administrate(
        ServicePlan plan,
        long transferLimitBytes,
        Instant expiresAt,
        Instant now
    ) {
        administrate(plan, transferLimitBytes, expiresAt, now,
            plan == null ? resetPolicy : effectivePlanPolicy(plan));
    }

    public void administrate(
        ServicePlan plan,
        long transferLimitBytes,
        Instant expiresAt,
        Instant now,
        TrafficResetPolicy effectiveResetPolicy
    ) {
        if (transferLimitBytes <= 0) {
            throw new IllegalArgumentException(
                "A traffic allowance must be greater than zero"
            );
        }
        TrafficResetPolicy previousPolicy = resetPolicy;
        if (plan != null) {
            this.plan = plan;
            this.planName = plan.getName();
            this.speedLimitMbps = plan.getSpeedLimitMbps();
            this.resetPolicy = plan.getPlanType()
                    == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
                    || trial
                ? TrafficResetPolicy.NEVER
                : effectiveResetPolicy;
        }
        this.transferLimitBytes = transferLimitBytes;
        this.expiresAt = expiresAt;
        if (previousPolicy != resetPolicy) {
            boolean active = !trial && canceledAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
            nextResetAt = active
                ? MonthlyResetSchedule.nextBoundary(resetPolicy, now, startsAt)
                : null;
        }
        // An operator granting a subscription is reactivating it, the same way
        // a paid order does.
        this.canceledAt = null;
        this.updatedAt = now;
    }

    private void applyPlan(
        ServicePlan plan,
        Instant expiresAt,
        TrafficResetPolicy effectiveResetPolicy,
        Instant now
    ) {
        TrafficResetPolicy previousPolicy = resetPolicy;
        this.plan = plan;
        this.planName = plan.getName();
        this.transferLimitBytes = plan.getTransferLimitBytes();
        this.speedLimitMbps = plan.getSpeedLimitMbps();
        this.resetPolicy = plan.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE
            ? TrafficResetPolicy.NEVER
            : effectiveResetPolicy;
        if (plan.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE) {
            nextResetAt = null;
        }
        if (previousPolicy != this.resetPolicy) {
            nextResetAt = MonthlyResetSchedule.nextBoundary(
                this.resetPolicy, now, startsAt
            );
        }
        this.expiresAt = expiresAt;
        // A paid order activates the subscription again, even one that was
        // cancelled while nothing was backing it.
        this.canceledAt = null;
        this.updatedAt = now;
    }

    private TrafficResetPolicy effectivePlanPolicy(ServicePlan plan) {
        if (plan.getPlanType()
                == com.sinx.platform.catalog.domain.PlanType.TRAFFIC_PACKAGE) {
            return TrafficResetPolicy.NEVER;
        }
        return plan.getResetPolicy() == null
            ? TrafficResetPolicy.MONTHLY_FROM_ACTIVATION
            : plan.getResetPolicy();
    }

    private void clearCounters(Instant now) {
        uploadedBytes = 0;
        downloadedBytes = 0;
        updatedAt = now;
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

    public com.sinx.platform.catalog.domain.PlanType getPlanType() {
        return plan.getPlanType();
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

    public Instant getCanceledAt() {
        return canceledAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isTrial() {
        return trial;
    }

    private long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
