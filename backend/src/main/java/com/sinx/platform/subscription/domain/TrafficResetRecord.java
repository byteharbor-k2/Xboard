package com.sinx.platform.subscription.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A traffic reset that has happened, whoever pushed the button.
 *
 * The counters only remember what a consumption report set them to last; a
 * reset zeroes them and along goes any evidence of how much had actually been
 * used. This row keeps that evidence: who the account is, which entitlement
 * ran the cycle, at what instant the counters fell to zero and what they read
 * just before, so an administrator can answer "was it used up" after the
 * fact.
 *
 * It is written once and never updated, and it carries the entitlement's id
 * without a reference to the row, whose plan and limits are rewritten each
 * time the customer buys something else; the instant alone is history.
 */
@Entity
@Table(name = "traffic_reset_records")
public class TrafficResetRecord {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "entitlement_id", nullable = false, updatable = false)
    private UUID entitlementId;

    /** When the counters were zeroed; a catch-up reset records when it ran. */
    @Column(name = "reset_at", nullable = false, updatable = false)
    private Instant resetAt;

    @Column(name = "uploaded_bytes_before", nullable = false, updatable = false)
    private long uploadedBytesBefore;

    @Column(name = "downloaded_bytes_before", nullable = false, updatable = false)
    private long downloadedBytesBefore;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reset_kind", nullable = false, length = 16, updatable = false)
    private TrafficResetKind resetKind;

    @Column(name = "traffic_cycle_id", updatable = false)
    private UUID trafficCycleId;

    protected TrafficResetRecord() {
    }

    public static TrafficResetRecord create(
        UUID userId,
        UUID entitlementId,
        Instant resetAt,
        long uploadedBytesBefore,
        long downloadedBytesBefore,
        Instant now,
        TrafficResetKind resetKind,
        UUID trafficCycleId
    ) {
        TrafficResetRecord record = new TrafficResetRecord();
        record.id = UUID.randomUUID();
        record.userId = userId;
        record.entitlementId = entitlementId;
        record.resetAt = resetAt;
        record.uploadedBytesBefore = uploadedBytesBefore;
        record.downloadedBytesBefore = downloadedBytesBefore;
        record.createdAt = now;
        record.resetKind = resetKind;
        record.trafficCycleId = trafficCycleId;
        return record;
    }

    /** Source-compatible manual-reset factory for existing focused callers. */
    public static TrafficResetRecord create(UUID userId, UUID entitlementId,
        Instant resetAt, long uploadedBytesBefore, long downloadedBytesBefore,
        Instant now) {
        return create(userId, entitlementId, resetAt, uploadedBytesBefore,
            downloadedBytesBefore, now, TrafficResetKind.MANUAL, null);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getEntitlementId() {
        return entitlementId;
    }

    public Instant getResetAt() {
        return resetAt;
    }

    public long getUploadedBytesBefore() {
        return uploadedBytesBefore;
    }

    public long getDownloadedBytesBefore() {
        return downloadedBytesBefore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public TrafficResetKind getResetKind() {
        return resetKind;
    }

    public UUID getTrafficCycleId() {
        return trafficCycleId;
    }
}
