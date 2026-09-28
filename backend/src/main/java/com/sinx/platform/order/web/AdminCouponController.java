package com.sinx.platform.order.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sinx.platform.order.application.AdminCouponView;
import com.sinx.platform.order.application.AdminCouponService;
import com.sinx.platform.order.domain.CouponDiscountType;

/**
 * Coupon administration on the Xboard-compatible surface.
 *
 * Access is already decided by the security configuration, which requires both
 * the admin role and the admin token scope for everything under
 * {@code /api/v2/admin}, so these methods carry no checks of their own. The
 * coupon is validated here rather than at the checkout: a definition that
 * cannot be created is one that can never fail a customer's quote.
 */
@RestController
@RequestMapping("/api/v2/admin/coupon")
public class AdminCouponController {

    private static final int DEFAULT_LIMIT = 100;

    private final AdminCouponService coupons;

    public AdminCouponController(AdminCouponService coupons) {
        this.coupons = coupons;
    }

    @GetMapping("/list")
    XboardResponse<List<AdminCouponView>> list(
        @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT)
        int limit
    ) {
        return XboardResponse.of(coupons.list(limit));
    }

    @PostMapping("/create")
    XboardResponse<AdminCouponView> create(@RequestBody CouponRequest request) {
        return XboardResponse.of(coupons.create(request.toDraft()));
    }

    @PostMapping("/update")
    XboardResponse<AdminCouponView> update(@RequestBody CouponRequest request) {
        return XboardResponse.of(coupons.update(request.id(), request.toDraft()));
    }

    @PostMapping("/delete")
    XboardResponse<Boolean> delete(@RequestBody IdRequest request) {
        coupons.delete(request.id());
        return XboardResponse.of(true);
    }

    /**
     * One coupon definition. {@code id} is only read by {@code update}.
     *
     * Epoch-second timestamps, matching the original panel's admin shape. An
     * absent bound means "open on that side"; {@code enabled} defaults on so a
     * bare create is a live coupon.
     */
    record CouponRequest(
        UUID id,
        String code,
        String name,
        @JsonProperty("discount_type") CouponDiscountType discountType,
        @JsonProperty("discount_value") Long discountValue,
        @JsonProperty("starts_at") Long startsAt,
        @JsonProperty("ends_at") Long endsAt,
        @JsonProperty("max_redemptions") Integer maxRedemptions,
        @JsonProperty("max_redemptions_per_user") Integer maxRedemptionsPerUser,
        @JsonProperty("limited_plan_ids") List<String> limitedPlanIds,
        @JsonProperty("limited_periods") List<String> limitedPeriods,
        Boolean enabled
    ) {

        AdminCouponService.CouponDraft toDraft() {
            return new AdminCouponService.CouponDraft(
                code,
                name,
                discountType,
                discountValue,
                startsAt == null ? null : Instant.ofEpochSecond(startsAt),
                endsAt == null ? null : Instant.ofEpochSecond(endsAt),
                maxRedemptions,
                maxRedemptionsPerUser,
                limitedPlanIds,
                limitedPeriods,
                enabled == null || enabled
            );
        }
    }

    record IdRequest(UUID id) {
    }

    record XboardResponse<T>(T data) {
        static <T> XboardResponse<T> of(T data) {
            return new XboardResponse<>(data);
        }
    }
}
