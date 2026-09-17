-- Payment. The original panel stores one row per configured payment method -
-- not per gateway - so a merchant can run several instances of the same
-- gateway side by side: one Epay endpoint taking WeChat and Alipay, another
-- pointing at a BEpusdt upstream for crypto. This keeps that shape.

CREATE TABLE payment_methods (
    id UUID PRIMARY KEY,
    -- Public identifier in the notify URL. Unguessable, unlike a sortable id:
    -- the callback endpoint is unauthenticated and every other credential it
    -- carries is in the query string.
    uuid VARCHAR(32) NOT NULL,
    -- The gateway implementation, e.g. EPAY. Which config keys exist is the
    -- gateway's business, not this table's.
    gateway VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    icon VARCHAR(255),
    -- Gateway credentials as JSON, keyed by the gateway's own form fields.
    config TEXT NOT NULL DEFAULT '{}',
    -- Swap the host in the notify URL for this, for panels behind a proxy the
    -- gateway cannot reach directly.
    notify_domain VARCHAR(128),
    -- Minor units for the fixed part, whole percent for the other. Both are
    -- added to what the customer pays, never to what the order is worth.
    handling_fee_fixed BIGINT,
    handling_fee_percent NUMERIC(5, 2),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_payment_methods_fee_fixed CHECK (
        handling_fee_fixed IS NULL OR handling_fee_fixed >= 0
    ),
    CONSTRAINT ck_payment_methods_fee_percent CHECK (
        handling_fee_percent IS NULL
        OR (handling_fee_percent >= 0 AND handling_fee_percent <= 100)
    )
);

CREATE UNIQUE INDEX uq_payment_methods_uuid ON payment_methods (uuid);
CREATE INDEX idx_payment_methods_sort ON payment_methods (sort_order);

-- Which method an order was checked out with, plus the surcharge it added.
-- Recorded separately from total_amount so the order's own value stays the
-- value it was priced at, whatever the customer was asked to pay on top.
ALTER TABLE orders
    ADD COLUMN payment_method_id UUID REFERENCES payment_methods (id)
        ON DELETE RESTRICT,
    ADD COLUMN gateway VARCHAR(32),
    ADD COLUMN handling_amount BIGINT NOT NULL DEFAULT 0;

ALTER TABLE orders
    ADD CONSTRAINT ck_orders_handling_amount CHECK (handling_amount >= 0);
