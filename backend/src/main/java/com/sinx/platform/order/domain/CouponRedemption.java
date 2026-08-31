package com.sinx.platform.order.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One use of a coupon, so per-customer limits can be enforced. */
@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CouponRedemption() {
    }

    public static CouponRedemption create(
        UUID couponId,
        UUID userId,
        UUID orderId,
        Instant now
    ) {
        CouponRedemption redemption = new CouponRedemption();
        redemption.id = UUID.randomUUID();
        redemption.couponId = couponId;
        redemption.userId = userId;
        redemption.orderId = orderId;
        redemption.createdAt = now;
        return redemption;
    }

    public UUID getId() {
        return id;
    }
}
