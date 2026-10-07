package com.sinx.platform.subscription.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.subscription.domain.PaidTrafficResetClaim;

public interface PaidTrafficResetClaimRepository
    extends JpaRepository<PaidTrafficResetClaim, UUID> {
    boolean existsByUserIdAndCycleEnd(UUID userId, Instant cycleEnd);
}
