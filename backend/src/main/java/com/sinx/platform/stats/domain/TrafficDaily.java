package com.sinx.platform.stats.domain;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * One daily slice of billed traffic.
 *
 * A row is written, and only written, inside the node traffic report that
 * charges the account - users whose report was skipped because they are no
 * longer eligible for the node do not appear at all, so this ledger is a
 * frozen copy of the billing stance and can settle complaining customers
 * against it.
 *
 * {@code uploadBytes} and {@code downloadBytes} hold the raw bytes the node
 * carried; {@code billedBytes} is what the entitlement counters actually
 * charged for them (raw deltas after the node rate multiplier). Aggregating
 * the billed column over an account reproduces the account's counters.
 */
@Entity
@Table(name = "traffic_daily")
@IdClass(TrafficDailyId.class)
public class TrafficDaily {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Id
    @Column(name = "node_id", nullable = false, updatable = false)
    private long nodeId;

    @Id
    @Column(name = "day", nullable = false, updatable = false)
    private LocalDate day;

    @Column(name = "upload_bytes", nullable = false)
    private long uploadBytes;

    @Column(name = "download_bytes", nullable = false)
    private long downloadBytes;

    @Column(name = "billed_bytes", nullable = false)
    private long billedBytes;

    protected TrafficDaily() {
    }

    public UUID getUserId() {
        return userId;
    }

    public long getNodeId() {
        return nodeId;
    }

    public LocalDate getDay() {
        return day;
    }

    public long getUploadBytes() {
        return uploadBytes;
    }

    public long getDownloadBytes() {
        return downloadBytes;
    }

    public long getBilledBytes() {
        return billedBytes;
    }
}
