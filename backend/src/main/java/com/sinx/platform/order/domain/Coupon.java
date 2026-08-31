package com.sinx.platform.order.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 16)
    private CouponDiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private long discountValue;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    @Column(name = "redemptions_used", nullable = false)
    private int redemptionsUsed;

    @Column(name = "max_redemptions_per_user")
    private Integer maxRedemptionsPerUser;

    @Column(name = "limited_plan_ids", nullable = false, columnDefinition = "text")
    private String limitedPlanIds = "[]";

    @Column(name = "limited_periods", nullable = false, columnDefinition = "text")
    private String limitedPeriods = "[]";

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Coupon() {
    }

    public static Coupon create(
        String code,
        String name,
        CouponDiscountType discountType,
        long discountValue,
        Instant now
    ) {
        Coupon coupon = new Coupon();
        coupon.id = UUID.randomUUID();
        coupon.code = code;
        coupon.name = name;
        coupon.discountType = discountType;
        coupon.discountValue = discountValue;
        coupon.createdAt = now;
        coupon.updatedAt = now;
        return coupon;
    }

    /**
     * The discount this coupon takes off {@code amountMinor}, never more than
     * the amount itself.
     */
    public long discountFor(long amountMinor) {
        long discount = discountType == CouponDiscountType.FIXED_AMOUNT
            ? discountValue
            : amountMinor * discountValue / 100;
        return Math.min(Math.max(discount, 0), Math.max(amountMinor, 0));
    }

    public boolean isRedeemableAt(Instant now) {
        if (!enabled) {
            return false;
        }
        if (startsAt != null && now.isBefore(startsAt)) {
            return false;
        }
        if (endsAt != null && !now.isBefore(endsAt)) {
            return false;
        }
        return maxRedemptions == null || redemptionsUsed < maxRedemptions;
    }

    public void recordRedemption(Instant now) {
        redemptionsUsed += 1;
        updatedAt = now;
    }

    /** Hands a redemption back when the order it belonged to is cancelled. */
    public void releaseRedemption(Instant now) {
        if (redemptionsUsed == 0) {
            return;
        }
        redemptionsUsed -= 1;
        updatedAt = now;
    }

    public void configureLimits(
        Instant startsAt,
        Instant endsAt,
        Integer maxRedemptions,
        Integer maxRedemptionsPerUser,
        String limitedPlanIds,
        String limitedPeriods,
        boolean enabled,
        Instant now
    ) {
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.maxRedemptions = maxRedemptions;
        this.maxRedemptionsPerUser = maxRedemptionsPerUser;
        this.limitedPlanIds = limitedPlanIds == null ? "[]" : limitedPlanIds;
        this.limitedPeriods = limitedPeriods == null ? "[]" : limitedPeriods;
        this.enabled = enabled;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public CouponDiscountType getDiscountType() {
        return discountType;
    }

    public long getDiscountValue() {
        return discountValue;
    }

    public Integer getMaxRedemptionsPerUser() {
        return maxRedemptionsPerUser;
    }

    public String getLimitedPlanIds() {
        return limitedPlanIds;
    }

    public String getLimitedPeriods() {
        return limitedPeriods;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
