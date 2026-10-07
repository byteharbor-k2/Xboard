package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** A successfully paid user reset consumes precisely one monthly-cycle allowance. */
@Entity
@Table(name = "paid_traffic_reset_claims", uniqueConstraints =
    @UniqueConstraint(name = "uq_paid_reset_user_cycle", columnNames = {"user_id", "cycle_end"}))
public class PaidTrafficResetClaim {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "cycle_end", nullable = false, updatable = false)
    private Instant cycleEnd;

    @Column(name = "trade_no", nullable = false, unique = true, length = 32, updatable = false)
    private String tradeNo;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    protected PaidTrafficResetClaim() { }

    public static PaidTrafficResetClaim create(UUID userId, Instant cycleEnd,
        String tradeNo, Instant now) {
        PaidTrafficResetClaim claim = new PaidTrafficResetClaim();
        claim.id = UUID.randomUUID();
        claim.userId = userId;
        claim.cycleEnd = cycleEnd;
        claim.tradeNo = tradeNo;
        claim.claimedAt = now;
        return claim;
    }
}
