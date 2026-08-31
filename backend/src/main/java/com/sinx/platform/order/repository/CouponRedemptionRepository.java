package com.sinx.platform.order.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.order.domain.CouponRedemption;

public interface CouponRedemptionRepository
    extends JpaRepository<CouponRedemption, UUID> {

    long countByCouponIdAndUserId(UUID couponId, UUID userId);
}
