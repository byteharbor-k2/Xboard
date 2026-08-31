-- Ordering. Money is stored in minor units (cents) as BIGINT throughout, the
-- same unit the plan prices already use, so no rounding happens between the
-- catalogue and the order.

ALTER TABLE users
    ADD COLUMN balance_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE users
    ADD CONSTRAINT ck_users_balance_non_negative CHECK (balance_minor >= 0);

CREATE TABLE coupons (
    id UUID PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    discount_type VARCHAR(16) NOT NULL,
    -- Minor units for FIXED_AMOUNT, whole percent for PERCENTAGE.
    discount_value BIGINT NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE,
    ends_at TIMESTAMP WITH TIME ZONE,
    -- NULL means unlimited, matching the original panel's semantics.
    max_redemptions INTEGER,
    redemptions_used INTEGER NOT NULL DEFAULT 0,
    max_redemptions_per_user INTEGER,
    limited_plan_ids TEXT NOT NULL DEFAULT '[]',
    limited_periods TEXT NOT NULL DEFAULT '[]',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_coupons_discount_type
        CHECK (discount_type IN ('FIXED_AMOUNT', 'PERCENTAGE')),
    CONSTRAINT ck_coupons_discount_value CHECK (discount_value >= 0),
    CONSTRAINT ck_coupons_redemptions CHECK (redemptions_used >= 0)
);

CREATE UNIQUE INDEX uq_coupons_code_normalized ON coupons (LOWER(code));

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    trade_no VARCHAR(32) NOT NULL,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    plan_id UUID NOT NULL REFERENCES service_plans (id) ON DELETE RESTRICT,
    -- Snapshot: the plan may be renamed or repriced after the order is placed.
    plan_name VARCHAR(255) NOT NULL,
    period VARCHAR(24) NOT NULL,
    order_type VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    original_amount BIGINT NOT NULL,
    discount_amount BIGINT NOT NULL DEFAULT 0,
    surplus_amount BIGINT NOT NULL DEFAULT 0,
    -- Left over when the unused value of the previous plan exceeds this order.
    surplus_credit BIGINT NOT NULL DEFAULT 0,
    balance_amount BIGINT NOT NULL DEFAULT 0,
    total_amount BIGINT NOT NULL,
    coupon_id UUID REFERENCES coupons (id) ON DELETE SET NULL,
    -- Orders whose value was consumed by this one's surplus deduction.
    surplus_order_ids TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    paid_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_orders_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'CANCELLED', 'COMPLETED', 'DISCOUNTED')
    ),
    CONSTRAINT ck_orders_type CHECK (
        order_type IN ('NEW_PURCHASE', 'RENEWAL', 'UPGRADE', 'RESET_TRAFFIC')
    ),
    CONSTRAINT ck_orders_amounts CHECK (
        original_amount >= 0
        AND discount_amount >= 0
        AND surplus_amount >= 0
        AND surplus_credit >= 0
        AND balance_amount >= 0
        AND total_amount >= 0
    )
);

CREATE UNIQUE INDEX uq_orders_trade_no ON orders (trade_no);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE coupon_redemptions (
    id UUID PRIMARY KEY,
    coupon_id UUID NOT NULL REFERENCES coupons (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    order_id UUID NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_coupon_redemptions_coupon_user
    ON coupon_redemptions (coupon_id, user_id);
CREATE UNIQUE INDEX uq_coupon_redemptions_order
    ON coupon_redemptions (order_id);
