-- A cashier URL can remain payable after another checkout or a merchant edit.
-- Preserve the exact terms and credentials used for every URL rather than
-- treating the mutable payment_methods row as a receipt's authority.
CREATE TABLE payment_attempts (
    id UUID PRIMARY KEY,
    trade_no VARCHAR(32) NOT NULL REFERENCES orders (trade_no) ON DELETE RESTRICT,
    buyer_user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    method_id UUID NOT NULL,
    method_uuid VARCHAR(32) NOT NULL,
    gateway_url VARCHAR(512) NOT NULL,
    merchant_identity VARCHAR(120) NOT NULL,
    method_name VARCHAR(120) NOT NULL,
    method_icon VARCHAR(255),
    gateway VARCHAR(32) NOT NULL,
    merchant_config TEXT NOT NULL,
    fee_fixed_minor BIGINT,
    fee_percent NUMERIC(5, 2),
    order_amount_minor BIGINT NOT NULL,
    handling_fee_minor BIGINT NOT NULL,
    payable_amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_payment_attempts_amounts CHECK (
        order_amount_minor > 0
        AND handling_fee_minor >= 0
        AND payable_amount_minor = order_amount_minor + handling_fee_minor
    )
);

CREATE INDEX idx_payment_attempts_callback
    ON payment_attempts (trade_no, method_uuid, gateway, created_at DESC);

-- Match the gateway endpoint identity used at runtime: EPay's config.url and
-- config.pid, never notify_domain or the site's public URL. If legacy
-- configuration has no usable endpoint, include the old method UUID instead
-- of sharing a global unknown key across unrelated merchants/platforms.
CREATE FUNCTION sinx_payment_gateway_url_key(raw_url TEXT, method_uuid TEXT)
RETURNS TEXT
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    candidate TEXT := NULLIF(BTRIM(raw_url), '');
    parts TEXT[];
    protocol TEXT;
    authority TEXT;
    path TEXT;
    query TEXT;
BEGIN
    IF candidate IS NULL THEN
        RETURN 'legacy://unknown/' || LOWER(COALESCE(method_uuid, 'unknown'));
    END IF;
    IF LOWER(candidate) NOT LIKE 'http://%'
            AND LOWER(candidate) NOT LIKE 'https://%' THEN
        candidate := 'https://' || candidate;
    END IF;
    parts := REGEXP_MATCH(candidate,
        '^(https?)://([^/?#]+)([^?#]*)([?][^#]*)?(#.*)?$', 'i');
    IF parts IS NULL THEN
        RETURN 'legacy://unknown/' || LOWER(COALESCE(method_uuid, 'unknown'));
    END IF;
    protocol := LOWER(parts[1]);
    authority := LOWER(parts[2]);
    IF protocol = 'https' THEN
        authority := REGEXP_REPLACE(authority, ':443$', '');
    ELSE
        authority := REGEXP_REPLACE(authority, ':80$', '');
    END IF;
    path := REGEXP_REPLACE(COALESCE(parts[3], ''), '/+$', '');
    query := COALESCE(parts[4], '');
    RETURN protocol || '://' || authority || path || query;
END;
$$;

-- Preserve an initial best-effort snapshot for all existing cashier links,
-- including orders already cancelled or manually settled. A deployment cannot
-- recover credentials changed before this migration, but unchanged legacy links
-- retain their original callback path and fee.
WITH legacy_attempt_data AS (
    SELECT o.trade_no,
           COALESCE(o.commission_buyer_user_id, o.user_id) AS buyer_user_id,
           pm.id AS method_id, pm.uuid AS method_uuid,
           pm.name AS method_name, pm.icon AS method_icon, o.gateway,
            sinx_payment_gateway_url_key(
                pm.config::jsonb ->> 'url', pm.uuid
            ) AS gateway_url,
           COALESCE(BTRIM(pm.config::jsonb ->> 'pid'), '') AS merchant_identity,
           pm.config AS merchant_config,
           pm.handling_fee_fixed AS fee_fixed_minor,
           pm.handling_fee_percent AS fee_percent,
           o.total_amount AS order_amount_minor,
           o.handling_amount AS handling_fee_minor,
           o.total_amount + o.handling_amount AS payable_amount_minor,
           o.currency, o.updated_at AS created_at
    FROM orders o
    JOIN payment_methods pm ON pm.id = o.payment_method_id
    WHERE o.gateway IS NOT NULL AND o.total_amount > 0
)
INSERT INTO payment_attempts (
    id, trade_no, buyer_user_id, method_id, method_uuid, method_name, method_icon, gateway,
    gateway_url, merchant_identity, merchant_config, fee_fixed_minor, fee_percent, order_amount_minor,
    handling_fee_minor, payable_amount_minor, currency, created_at
)
SELECT gen_random_uuid(), trade_no, buyer_user_id, method_id, method_uuid,
       method_name, method_icon, gateway, gateway_url, merchant_identity,
       merchant_config, fee_fixed_minor, fee_percent, order_amount_minor,
       handling_fee_minor, payable_amount_minor, currency, created_at
FROM legacy_attempt_data;

-- A gateway transaction is money received, distinct from the order state.
-- The unique transaction key is the durable idempotency boundary even when a
-- cashier retries its callback or the order was already settled another way.
CREATE TABLE payment_receipts (
    id UUID PRIMARY KEY,
    -- NULL denotes a historical payment identity backfilled from callback_no;
    -- no fabricated checkout attempt is attributed to a payment already handled.
    attempt_id UUID REFERENCES payment_attempts (id) ON DELETE RESTRICT,
    gateway VARCHAR(32) NOT NULL,
    gateway_url VARCHAR(512) NOT NULL,
    merchant_identity VARCHAR(120) NOT NULL,
    transaction_id VARCHAR(128) NOT NULL,
    trade_no VARCHAR(32) NOT NULL REFERENCES orders (trade_no) ON DELETE RESTRICT,
    amount_minor BIGINT NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_payment_receipts_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_payment_receipts_outcome CHECK (
        outcome IN ('ORDER_SETTLED', 'BALANCE_CREDITED')
    )
);

CREATE UNIQUE INDEX uq_payment_receipts_gateway_transaction
    ON payment_receipts (
        LOWER(gateway), gateway_url, merchant_identity, transaction_id
    );
CREATE INDEX idx_payment_receipts_trade_no ON payment_receipts (trade_no);

-- callback_no is set only after a gateway has already settled the order. Keep
-- that transaction identity to prevent a post-deployment replay from crediting
-- the already-paid order again. Manual and zero-pay sentinels are not receipts.
WITH historical_receipts AS (
    SELECT LOWER(o.gateway) AS gateway,
           sinx_payment_gateway_url_key(
               pm.config::jsonb ->> 'url', pm.uuid
           ) AS gateway_url,
           COALESCE(BTRIM(pm.config::jsonb ->> 'pid'), '') AS merchant_identity,
           o.callback_no AS transaction_id, o.trade_no,
           o.total_amount + o.handling_amount AS amount_minor,
           CASE WHEN o.settlement_outcome = 'BALANCE_RETURNED'
               THEN 'BALANCE_CREDITED' ELSE 'ORDER_SETTLED' END AS outcome,
           COALESCE(o.paid_at, o.updated_at) AS received_at
    FROM orders o
    JOIN payment_methods pm ON pm.id = o.payment_method_id
    WHERE o.gateway IS NOT NULL
      AND o.total_amount > 0
      AND o.status IN ('PROCESSING', 'COMPLETED', 'DISCOUNTED')
      AND NULLIF(BTRIM(o.callback_no), '') IS NOT NULL
      AND o.callback_no NOT IN ('manual_operation', 'auto_settled')
      AND o.paid_at IS NOT NULL
),
one_receipt_per_transaction AS (
    -- Reused historical callback_no values still identify a transaction that
    -- was processed. Keep one deterministic identity so it cannot be credited
    -- again against another order after upgrade.
    SELECT DISTINCT ON (gateway, gateway_url, merchant_identity, transaction_id)
           gateway, gateway_url, merchant_identity, transaction_id, trade_no,
           amount_minor, outcome, received_at
    FROM historical_receipts
    ORDER BY gateway, gateway_url, merchant_identity, transaction_id,
             received_at, trade_no
)
INSERT INTO payment_receipts (
    id, attempt_id, gateway, gateway_url, merchant_identity, transaction_id,
    trade_no, amount_minor, outcome, received_at
)
SELECT gen_random_uuid(), NULL, gateway, gateway_url, merchant_identity,
       transaction_id, trade_no, amount_minor, outcome, received_at
FROM one_receipt_per_transaction;

DROP FUNCTION sinx_payment_gateway_url_key(TEXT, TEXT);
