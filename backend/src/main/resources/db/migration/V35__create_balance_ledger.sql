-- Existing cash is the source of truth at cutover. Each existing account gets
-- one opening anchor; it is not a new credit and historical orders/commissions
-- are intentionally not reconstructed as cash movements.
CREATE TABLE balance_logs (
    id UUID PRIMARY KEY,
    ledger_sequence BIGINT GENERATED ALWAYS AS IDENTITY NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    type VARCHAR(32) NOT NULL,
    amount_minor BIGINT NOT NULL,
    balance_after_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'CNY',
    trade_no VARCHAR(32) REFERENCES orders (trade_no) ON DELETE RESTRICT,
    commission_level INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_balance_logs_type CHECK (type IN (
        'OPENING_BALANCE', 'ORDER_PAYMENT', 'ORDER_REFUND',
        'SURPLUS_CREDIT', 'COMMISSION_CREDIT'
    )),
    CONSTRAINT ck_balance_logs_amount CHECK (
        amount_minor <> 0 OR type = 'OPENING_BALANCE'
    ),
    CONSTRAINT ck_balance_logs_opening CHECK (
        type <> 'OPENING_BALANCE' OR balance_after_minor = amount_minor
    ),
    CONSTRAINT ck_balance_logs_commission_level CHECK (
        (type = 'COMMISSION_CREDIT' AND commission_level IS NOT NULL
            AND commission_level BETWEEN 1 AND 3)
        OR (type <> 'COMMISSION_CREDIT' AND commission_level IS NULL)
    )
);

CREATE INDEX idx_balance_logs_user_sequence
    ON balance_logs (user_id, ledger_sequence DESC);
CREATE UNIQUE INDEX uq_balance_logs_commission_trade_level
    ON balance_logs (trade_no, commission_level)
    WHERE type = 'COMMISSION_CREDIT';
CREATE UNIQUE INDEX uq_balance_logs_order_mutation
    ON balance_logs (trade_no, type)
    WHERE trade_no IS NOT NULL AND type IN (
        'ORDER_PAYMENT', 'ORDER_REFUND', 'SURPLUS_CREDIT'
    );

INSERT INTO balance_logs (
    id, user_id, type, amount_minor, balance_after_minor, currency,
    trade_no, commission_level, created_at
)
SELECT gen_random_uuid(), id, 'OPENING_BALANCE', balance_minor, balance_minor,
       'CNY', NULL, NULL, CURRENT_TIMESTAMP
FROM users;
