package com.sinx.platform.subscription.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.sinx.platform.subscription.domain.PaidTrafficResetClaim;

public interface PaidTrafficResetClaimRepository
    extends JpaRepository<PaidTrafficResetClaim, UUID> {
    boolean existsByUserIdAndCycleId(UUID userId, UUID cycleId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
        update PaidTrafficResetClaim claim set claim.userId = :toUserId
        where claim.userId = :fromUserId
        """)
    int moveOwnership(@org.springframework.data.repository.query.Param("fromUserId") UUID fromUserId,
        @org.springframework.data.repository.query.Param("toUserId") UUID toUserId);
}
