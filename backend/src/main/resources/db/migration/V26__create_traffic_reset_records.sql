-- Traffic reset cycles and their history.
--
-- The reset for a MONTHLY_FROM_ACTIVATION entitlement fires when a month has
-- passed since its activation (or, from then on, since its last reset). The
-- anchor for that chain already exists on subscription_entitlements as the
-- next_reset_at column - the account dashboard's "next reset" display has been
-- reading it all along - but it has stayed NULL everywhere, because no reset
-- cron has existed and administrators have pressed the reset button by hand.
--
-- The backfill seeds the chain anchor with the activation instant for every
-- monthly entitlement: the next job walk derives the first due boundary from
-- there, so a customer keeps the anniversary day of their activation day.
-- Policies without a monthly cycle - the traffic packages and the NEVER
-- entitlements - keep their NULL.

UPDATE subscription_entitlements
SET next_reset_at = starts_at
WHERE reset_policy = 'MONTHLY_FROM_ACTIVATION'
  AND next_reset_at IS NULL;

-- What was released, per reset. Read-only history: nothing writes a row but
-- the reset itself, and nothing updates or deletes one afterwards.
--
-- No reference is declared to the entitlement, whose row is rewritten as plans
-- change - history must survive whatever a plan switch does to it. The account
-- is restricted like the orders are (V17), so a customer who has reset history
-- is retired by suspension, not deletion.
CREATE TABLE traffic_reset_records (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    entitlement_id UUID NOT NULL,
    reset_at TIMESTAMP WITH TIME ZONE NOT NULL,
    uploaded_bytes_before BIGINT NOT NULL,
    downloaded_bytes_before BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_traffic_reset_records_usage CHECK (
        uploaded_bytes_before >= 0
        AND downloaded_bytes_before >= 0
    )
);

CREATE INDEX idx_traffic_reset_records_reset_at
    ON traffic_reset_records (reset_at DESC);

CREATE INDEX idx_traffic_reset_records_user
    ON traffic_reset_records (user_id);
