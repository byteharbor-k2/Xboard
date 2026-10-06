-- Commission eligibility and the payout amounts are captured when the order
-- is placed. A later settings change must not rewrite historical purchases.
ALTER TABLE users
    ADD COLUMN commission_type INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN commission_rate INTEGER;

ALTER TABLE orders
    ADD COLUMN invite_user_id UUID REFERENCES users (id) ON DELETE RESTRICT,
    ADD COLUMN commission_buyer_user_id UUID REFERENCES users (id) ON DELETE RESTRICT,
    ADD COLUMN commission_base BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN commission_balance BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN commission_status INTEGER,
    ADD COLUMN actual_commission_balance BIGINT NOT NULL DEFAULT 0;

-- Assignment may later move order ownership; commission and its audit trail
-- still refer to the original customer who placed the purchase.
UPDATE orders SET commission_buyer_user_id = user_id;

CREATE INDEX idx_orders_commission_queue
    ON orders (commission_status, updated_at)
    WHERE commission_status IN (0, 1);

CREATE TABLE commission_logs (
    id UUID PRIMARY KEY,
    invite_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    trade_no VARCHAR(32) NOT NULL REFERENCES orders (trade_no) ON DELETE RESTRICT,
    order_amount BIGINT NOT NULL,
    commission_base BIGINT NOT NULL,
    get_amount BIGINT NOT NULL,
    level INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_commission_logs_recipient_created
    ON commission_logs (invite_user_id, created_at DESC);
CREATE INDEX idx_commission_logs_trade_no ON commission_logs (trade_no);
