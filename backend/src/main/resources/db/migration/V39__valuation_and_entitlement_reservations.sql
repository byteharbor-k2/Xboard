ALTER TABLE subscription_entitlements
    ADD COLUMN traffic_cycle_start timestamptz,
    ADD COLUMN traffic_cycle_end timestamptz,
    ADD COLUMN traffic_cycle_id uuid,
    ADD COLUMN cycle_consumed_bytes bigint NOT NULL DEFAULT 0,
    ADD COLUMN surplus_reserved boolean NOT NULL DEFAULT false;

ALTER TABLE subscription_entitlements
    ADD CONSTRAINT ck_entitlement_cycle_consumed CHECK (cycle_consumed_bytes >= 0);

ALTER TABLE traffic_reset_records
    ADD COLUMN reset_kind varchar(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN traffic_cycle_id uuid,
    ADD CONSTRAINT ck_traffic_reset_records_kind
        CHECK (reset_kind IN ('AUTOMATIC', 'MANUAL', 'PAID'));

ALTER TABLE orders ADD COLUMN reset_cycle_start timestamptz;
ALTER TABLE orders ADD COLUMN reset_cycle_id uuid;
ALTER TABLE orders ADD COLUMN balance_payer_user_id uuid
    REFERENCES users(id) ON DELETE RESTRICT;
ALTER TABLE paid_traffic_reset_claims ADD COLUMN cycle_start timestamptz;
ALTER TABLE paid_traffic_reset_claims ADD COLUMN traffic_cycle_id uuid;

-- Start from the policy snapshot currently on each entitlement. A completed
-- reset or a successful claim below takes precedence over the mutable scheduled
-- next_reset_at when reconstructing the actual in-progress cycle.
UPDATE subscription_entitlements
SET traffic_cycle_start = GREATEST(starts_at, CASE reset_policy
        WHEN 'MONTHLY_FROM_ACTIVATION' THEN next_reset_at - interval '1 month'
        WHEN 'YEARLY_FROM_ACTIVATION' THEN next_reset_at - interval '1 year'
        WHEN 'FIRST_DAY_OF_MONTH' THEN
            (date_trunc('month', next_reset_at AT TIME ZONE 'Asia/Shanghai')
                - interval '1 month') AT TIME ZONE 'Asia/Shanghai'
        WHEN 'FIRST_DAY_OF_YEAR' THEN
            (date_trunc('year', next_reset_at AT TIME ZONE 'Asia/Shanghai')
                - interval '1 year') AT TIME ZONE 'Asia/Shanghai'
        ELSE NULL
    END),
    traffic_cycle_end = next_reset_at,
    traffic_cycle_id = gen_random_uuid()
WHERE next_reset_at IS NOT NULL AND is_trial = false;

UPDATE subscription_entitlements
SET traffic_cycle_start = starts_at,
    traffic_cycle_id = gen_random_uuid()
WHERE traffic_cycle_id IS NULL AND is_trial = false;

-- Older versions wrote the previous cycle's counters into the reset ledger.
-- Scheduled resets normally run within an hour of their boundary; mark that
-- boundary-closing row so it is not carried into the next cycle's consumption.
UPDATE traffic_reset_records r
SET reset_kind = 'AUTOMATIC'
FROM subscription_entitlements e
WHERE r.entitlement_id = e.id
  AND e.traffic_cycle_start IS NOT NULL
  AND r.reset_at >= e.starts_at
  AND r.reset_at >= e.traffic_cycle_start
  AND r.reset_at < e.traffic_cycle_start + interval '1 hour';

-- A later policy edit may have changed the entitlement's current boundary.
-- Successful reset orders retain the boundary that was actually offered, so
-- use those snapshots to recognize their preceding automatic rollover too.
UPDATE traffic_reset_records r
SET reset_kind = 'AUTOMATIC'
FROM paid_traffic_reset_claims c
JOIN orders paid_order ON paid_order.trade_no = c.trade_no
WHERE r.user_id = c.user_id
  AND r.reset_at < c.claimed_at
  AND (
      r.reset_at >= paid_order.reset_cycle_end - interval '1 month'
      AND r.reset_at < paid_order.reset_cycle_end - interval '1 month' + interval '1 hour'
      OR r.reset_at >= paid_order.reset_cycle_end - interval '1 year'
      AND r.reset_at < paid_order.reset_cycle_end - interval '1 year' + interval '1 hour'
  );

-- A paid reset and its reset ledger row share the exact transaction timestamp.
-- This recovers paid-reset history without mistaking it for a cycle rollover.
UPDATE traffic_reset_records r
SET reset_kind = 'PAID'
FROM paid_traffic_reset_claims c
WHERE r.user_id = c.user_id
  AND r.reset_at = c.claimed_at;

UPDATE paid_traffic_reset_claims
SET cycle_start = CASE
        WHEN EXISTS (
            SELECT 1
            FROM traffic_reset_records r
            JOIN subscription_entitlements e ON e.id = r.entitlement_id
            WHERE r.user_id = paid_traffic_reset_claims.user_id
              AND paid_traffic_reset_claims.claimed_at >= e.starts_at
              AND EXISTS (
                  SELECT 1 FROM orders reset_order
                  WHERE reset_order.trade_no = paid_traffic_reset_claims.trade_no
                    AND reset_order.user_id = e.user_id
                    AND reset_order.created_at >= e.starts_at
              )
              AND r.reset_kind = 'AUTOMATIC'
              AND r.reset_at >= e.starts_at
              AND r.reset_at < paid_traffic_reset_claims.claimed_at
        ) THEN (
            SELECT MAX(r.reset_at)
            FROM traffic_reset_records r
            JOIN subscription_entitlements e ON e.id = r.entitlement_id
            WHERE r.user_id = paid_traffic_reset_claims.user_id
              AND paid_traffic_reset_claims.claimed_at >= e.starts_at
              AND EXISTS (
                  SELECT 1 FROM orders reset_order
                  WHERE reset_order.trade_no = paid_traffic_reset_claims.trade_no
                    AND reset_order.user_id = e.user_id
                    AND reset_order.created_at >= e.starts_at
              )
              AND r.reset_kind = 'AUTOMATIC'
              AND r.reset_at >= e.starts_at
              AND r.reset_at < paid_traffic_reset_claims.claimed_at
        )
        WHEN EXISTS (
            SELECT 1 FROM subscription_entitlements e
            WHERE e.user_id = paid_traffic_reset_claims.user_id
              AND paid_traffic_reset_claims.claimed_at >= e.starts_at
              AND EXISTS (
                  SELECT 1 FROM orders reset_order
                  WHERE reset_order.trade_no = paid_traffic_reset_claims.trade_no
                    AND reset_order.user_id = e.user_id
                    AND reset_order.created_at >= e.starts_at
              )
        ) THEN (
            SELECT e.starts_at FROM subscription_entitlements e
            WHERE e.user_id = paid_traffic_reset_claims.user_id
              AND paid_traffic_reset_claims.claimed_at >= e.starts_at
              AND EXISTS (
                  SELECT 1 FROM orders reset_order
                  WHERE reset_order.trade_no = paid_traffic_reset_claims.trade_no
                    AND reset_order.user_id = e.user_id
                    AND reset_order.created_at >= e.starts_at
              )
        )
        ELSE cycle_end - interval '1 month'
    END,
    traffic_cycle_id = gen_random_uuid();

-- An entitlement row is reused by later purchases. Historical automatic resets
-- remain classified, but only this activation can establish its live cycle.
UPDATE subscription_entitlements e
SET traffic_cycle_start = COALESCE((
        SELECT MAX(r.reset_at)
        FROM traffic_reset_records r
        WHERE r.entitlement_id = e.id
          AND r.reset_kind = 'AUTOMATIC'
          AND r.reset_at >= e.starts_at
          AND r.reset_at <= clock_timestamp()
    ), e.traffic_cycle_start)
WHERE e.traffic_cycle_id IS NOT NULL;

-- Manual reanchors and policy edits changed next_reset_at in the old schema.
-- A successful claim still belongs to the current cycle when no actual
-- automatic rollover followed it. Reuse that claim identity for every such
-- receipt, even when several old keys were created by repeated reanchoring.
WITH current_claim AS (
    SELECT e.id AS entitlement_id, e.user_id, c.traffic_cycle_id,
           c.cycle_start, c.cycle_end
    FROM subscription_entitlements e
    JOIN LATERAL (
        SELECT c.traffic_cycle_id, c.cycle_start, c.cycle_end, c.claimed_at
        FROM paid_traffic_reset_claims c
         WHERE c.user_id = e.user_id
           AND c.claimed_at >= e.starts_at
           AND EXISTS (
               SELECT 1 FROM orders reset_order
               WHERE reset_order.trade_no = c.trade_no
                 AND reset_order.user_id = e.user_id
                 AND reset_order.created_at >= e.starts_at
           )
           AND NOT EXISTS (
               SELECT 1 FROM traffic_reset_records r
               WHERE r.entitlement_id = e.id
                 AND r.reset_kind = 'AUTOMATIC'
                 AND r.reset_at >= e.starts_at
                AND r.reset_at > c.claimed_at
          )
        ORDER BY c.claimed_at ASC
        LIMIT 1
    ) c ON true
)
UPDATE subscription_entitlements e
SET traffic_cycle_id = c.traffic_cycle_id,
    traffic_cycle_start = c.cycle_start,
    traffic_cycle_end = c.cycle_end
FROM current_claim c
WHERE e.id = c.entitlement_id;

UPDATE paid_traffic_reset_claims c
SET traffic_cycle_id = e.traffic_cycle_id
FROM subscription_entitlements e
WHERE c.user_id = e.user_id
  AND c.claimed_at >= e.starts_at
  AND EXISTS (
      SELECT 1 FROM orders reset_order
      WHERE reset_order.trade_no = c.trade_no
        AND reset_order.user_id = e.user_id
        AND reset_order.created_at >= e.starts_at
  )
  AND NOT EXISTS (
      SELECT 1 FROM traffic_reset_records r
      WHERE r.entitlement_id = e.id
        AND r.reset_kind = 'AUTOMATIC'
        AND r.reset_at >= e.starts_at
        AND r.reset_at > c.claimed_at
  );

-- Preserve which funded cycle each reset actually changed. Automatic reset
-- rows close the old cycle; manual and paid rows consume traffic in the cycle
-- they reference.
UPDATE traffic_reset_records r
SET traffic_cycle_id = e.traffic_cycle_id
FROM subscription_entitlements e
WHERE r.entitlement_id = e.id
  AND r.reset_kind <> 'AUTOMATIC'
  AND r.reset_at >= e.starts_at
  AND r.reset_at >= e.traffic_cycle_start;

UPDATE traffic_reset_records r
SET traffic_cycle_id = c.traffic_cycle_id
FROM paid_traffic_reset_claims c
WHERE r.user_id = c.user_id
  AND r.reset_at = c.claimed_at;

UPDATE traffic_reset_records
SET traffic_cycle_id = gen_random_uuid()
WHERE traffic_cycle_id IS NULL;

-- The cycle-consumed counter contains only usage before manual/paid resets.
-- Current uploaded/downloaded counters are added by the application accessor,
-- and boundary-closing rows are deliberately excluded.
UPDATE subscription_entitlements e
SET cycle_consumed_bytes = LEAST(
    9223372036854775807::numeric,
    COALESCE((
        SELECT SUM(r.uploaded_bytes_before::numeric + r.downloaded_bytes_before::numeric)
        FROM traffic_reset_records r
        WHERE r.entitlement_id = e.id
          AND r.reset_at >= e.starts_at
          AND r.traffic_cycle_id = e.traffic_cycle_id
          AND r.reset_kind IN ('MANUAL', 'PAID')
    ), 0)
)::bigint
WHERE e.traffic_cycle_id IS NOT NULL;

UPDATE orders o
SET reset_cycle_start = e.traffic_cycle_start,
    reset_cycle_id = e.traffic_cycle_id
FROM subscription_entitlements e
WHERE o.period = 'RESET_TRAFFIC'
  AND o.user_id = e.user_id
  AND o.created_at >= e.starts_at
  AND o.reset_cycle_end IS NOT NULL
  AND o.reset_cycle_end = e.traffic_cycle_end;

-- A pending order can be reassigned after its balance debit. Reconstruct that
-- payer from the atomic ledger posting, then use the immutable checkout buyer
-- and current order owner as fallbacks for orders without a wallet debit.
UPDATE orders o
SET balance_payer_user_id = COALESCE(
    (SELECT b.user_id FROM balance_logs b
     WHERE b.trade_no = o.trade_no AND b.type = 'ORDER_PAYMENT'
     ORDER BY b.ledger_sequence LIMIT 1),
    o.commission_buyer_user_id,
    o.user_id
);
CREATE INDEX idx_orders_balance_payer ON orders(balance_payer_user_id);

-- Legacy pending STANDARD orders already consumed an upgrade quote. Treat their
-- entitlement as reserved immediately so traffic cannot be consumed again.
UPDATE subscription_entitlements e
SET surplus_reserved = true
FROM orders o
WHERE o.user_id = e.user_id
  AND o.status = 'PENDING'
  AND o.deduction_mode = 'STANDARD'
  AND o.surplus_amount > 0;

ALTER TABLE paid_traffic_reset_claims ALTER COLUMN traffic_cycle_id SET NOT NULL;
ALTER TABLE paid_traffic_reset_claims
    DROP CONSTRAINT uq_paid_reset_user_cycle;
CREATE INDEX idx_paid_reset_user_cycle_identity
    ON paid_traffic_reset_claims (user_id, traffic_cycle_id);

CREATE INDEX idx_traffic_reset_records_cycle
    ON traffic_reset_records (entitlement_id, traffic_cycle_id, reset_kind);
