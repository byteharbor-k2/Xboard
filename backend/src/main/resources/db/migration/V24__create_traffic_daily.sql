-- Authority-sided daily traffic ledger.
--
-- One row per account, node and day, holding both:
--   * the raw bytes the node actually carried (upload_bytes / download_bytes),
--   * the bytes the billing actually charged (billed_bytes), i.e. the raw
--     deltas after the node's rate multiplier was applied.
-- Keeping both lets the account page show what was physically used while the
-- billed column reconciles byte for byte with the entitlement counters - the
-- evidence a complaint about billing asks for.
--
-- Rows are written inside the node traffic report transaction, when the usage
-- is charged, so skipped (not-eligible) users never appear here. The summary
-- aggregates deliberately exclude ineligible traffic for the same reason.
CREATE TABLE traffic_daily (
    user_id UUID NOT NULL,
    node_id BIGINT NOT NULL,
    day DATE NOT NULL,
    upload_bytes BIGINT NOT NULL DEFAULT 0,
    download_bytes BIGINT NOT NULL DEFAULT 0,
    billed_bytes BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_traffic_daily PRIMARY KEY (user_id, node_id, day)
);

CREATE INDEX idx_traffic_daily_user_day
    ON traffic_daily (user_id, day);

CREATE INDEX idx_traffic_daily_day
    ON traffic_daily (day);

CREATE INDEX idx_traffic_daily_node_day
    ON traffic_daily (node_id, day);
