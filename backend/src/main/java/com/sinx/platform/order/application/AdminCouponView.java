package com.sinx.platform.order.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.order.domain.Coupon;
import com.sinx.platform.order.domain.CouponDiscountType;

/**
 * One coupon as the admin API reports it, in the original panel's admin shape:
 * snake_case field names, epoch-second timestamps, amounts in minor units for
 * type FIXED_AMOUNT and whole percent for type PERCENTAGE.
 */
public record AdminCouponView(
    UUID id,
    String code,
    String name,
    @JsonProperty("discount_type") CouponDiscountType discountType,
    @JsonProperty("discount_value") long discountValue,
    @JsonProperty("starts_at") Long startsAt,
    @JsonProperty("ends_at") Long endsAt,
    @JsonProperty("max_redemptions") Integer maxRedemptions,
    @JsonProperty("redemptions_used") int redemptionsUsed,
    @JsonProperty("max_redemptions_per_user") Integer maxRedemptionsPerUser,
    @JsonProperty("limited_plan_ids") List<String> limitedPlanIds,
    @JsonProperty("limited_periods") List<String> limitedPeriods,
    boolean enabled,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt
) {
    static AdminCouponView from(
        Coupon coupon,
        List<String> limitedPlanIds,
        List<String> limitedPeriods
    ) {
        return new AdminCouponView(
            coupon.getId(),
            coupon.getCode(),
            coupon.getName(),
            coupon.getDiscountType(),
            coupon.getDiscountValue(),
            seconds(coupon.getStartsAt()),
            seconds(coupon.getEndsAt()),
            coupon.getMaxRedemptions(),
            coupon.getRedemptionsUsed(),
            coupon.getMaxRedemptionsPerUser(),
            limitedPlanIds,
            limitedPeriods,
            coupon.isEnabled(),
            seconds(coupon.getCreatedAt()),
            seconds(coupon.getUpdatedAt())
        );
    }

    private static Long seconds(Instant instant) {
        return instant == null ? null : instant.getEpochSecond();
    }
}
