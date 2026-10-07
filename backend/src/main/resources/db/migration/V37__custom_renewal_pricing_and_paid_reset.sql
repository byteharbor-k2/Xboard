ALTER TABLE orders
    ADD COLUMN deduction_mode varchar(24) NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN deferred_surplus_credit bigint NOT NULL DEFAULT 0,
    ADD COLUMN reset_cycle_end timestamptz,
    ADD COLUMN settlement_outcome varchar(24) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN returned_balance_minor bigint NOT NULL DEFAULT 0,
    ADD COLUMN coverage_start timestamptz,
    ADD COLUMN coverage_end timestamptz;

ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (
    status IN ('PENDING', 'PROCESSING', 'CANCELLED', 'COMPLETED', 'DISCOUNTED')
);
ALTER TABLE orders ADD CONSTRAINT ck_orders_settlement_outcome CHECK (
    settlement_outcome IN ('PENDING', 'SERVICE_FULFILLED', 'BALANCE_RETURNED')
);
ALTER TABLE orders ADD CONSTRAINT ck_orders_returned_balance CHECK (
    returned_balance_minor >= 0
);
ALTER TABLE orders ADD CONSTRAINT ck_orders_coverage_window CHECK (
    (coverage_start IS NULL AND coverage_end IS NULL)
    OR (coverage_start IS NOT NULL AND coverage_end IS NOT NULL
        AND coverage_end > coverage_start)
);
UPDATE orders SET settlement_outcome = 'SERVICE_FULFILLED'
WHERE status IN ('COMPLETED', 'DISCOUNTED');

CREATE TABLE paid_traffic_reset_claims (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cycle_end timestamptz NOT NULL,
    trade_no varchar(32) NOT NULL UNIQUE REFERENCES orders(trade_no),
    claimed_at timestamptz NOT NULL,
    CONSTRAINT uq_paid_reset_user_cycle UNIQUE (user_id, cycle_end)
);
