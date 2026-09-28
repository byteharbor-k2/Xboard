package com.sinx.platform.stats.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Composite identity of the daily traffic ledger: one row per account, node
 * and day. {@code userId} is the SQL account id, while {@code nodeId} and
 * {@code day} are shared with the node report stream that produced the row.
 */
public record TrafficDailyId(UUID userId, long nodeId, LocalDate day)
    implements Serializable {
}
