package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A successfully paid user reset consumes one allowance for its actual traffic cycle. */
@Entity
@Table(name = "paid_traffic_reset_claims")
public class PaidTrafficResetClaim {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "cycle_end", nullable = false, updatable = false)
    private Instant cycleEnd;

    @Column(name = "cycle_start", nullable = false, updatable = false)
    private Instant cycleStart;

    @Column(name = "traffic_cycle_id", nullable = false, updatable = false)
    private UUID cycleId;

    @Column(name = "trade_no", nullable = false, unique = true, length = 32, updatable = false)
    private String tradeNo;

    @Column(name = "claimed_at", nullable = false, updatable = false)
    private Instant claimedAt;

    protected PaidTrafficResetClaim() { }

    public static PaidTrafficResetClaim create(UUID userId, UUID cycleId,
        Instant cycleStart, Instant cycleEnd, String tradeNo, Instant now) {
        PaidTrafficResetClaim claim = new PaidTrafficResetClaim();
        claim.id = UUID.randomUUID();
        claim.userId = userId;
        claim.cycleEnd = cycleEnd;
        claim.cycleStart = cycleStart;
        claim.cycleId = cycleId;
        claim.tradeNo = tradeNo;
        claim.claimedAt = now;
        return claim;
    }

    public static PaidTrafficResetClaim create(UUID userId, Instant cycleStart,
        Instant cycleEnd, String tradeNo, Instant now) {
        return create(userId, UUID.randomUUID(), cycleStart, cycleEnd, tradeNo, now);
    }
}
