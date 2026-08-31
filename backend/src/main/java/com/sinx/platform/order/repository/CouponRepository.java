package com.sinx.platform.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sinx.platform.order.domain.Coupon;

import jakarta.persistence.LockModeType;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    @Query("select c from Coupon c where lower(c.code) = lower(:code)")
    Optional<Coupon> findByCode(@Param("code") String code);

    /** Locked so two orders cannot spend the last redemption of a coupon. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where lower(c.code) = lower(:code)")
    Optional<Coupon> findForUpdateByCode(@Param("code") String code);
}
