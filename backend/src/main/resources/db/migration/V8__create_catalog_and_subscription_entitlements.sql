CREATE TABLE service_plans (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    transfer_limit_bytes BIGINT NOT NULL,
    speed_limit_mbps INTEGER,
    device_limit INTEGER,
    reset_policy VARCHAR(32) NOT NULL,
    capacity_limit INTEGER,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    sellable BOOLEAN NOT NULL DEFAULT FALSE,
    renewable BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_service_plans_transfer
        CHECK (transfer_limit_bytes > 0),
    CONSTRAINT ck_service_plans_speed
        CHECK (speed_limit_mbps IS NULL OR speed_limit_mbps > 0),
    CONSTRAINT ck_service_plans_devices
        CHECK (device_limit IS NULL OR device_limit > 0),
    CONSTRAINT ck_service_plans_capacity
        CHECK (capacity_limit IS NULL OR capacity_limit > 0),
    CONSTRAINT ck_service_plans_reset_policy
        CHECK (reset_policy IN (
            'FIRST_DAY_OF_MONTH',
            'MONTHLY_FROM_ACTIVATION',
            'NEVER',
            'FIRST_DAY_OF_YEAR',
            'YEARLY_FROM_ACTIVATION'
        ))
);

CREATE INDEX idx_service_plans_catalog
    ON service_plans (published, sellable, sort_order);

CREATE TABLE service_plan_tags (
    plan_id UUID NOT NULL REFERENCES service_plans(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    label VARCHAR(48) NOT NULL,
    PRIMARY KEY (plan_id, position)
);

CREATE TABLE service_plan_prices (
    id UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES service_plans(id) ON DELETE CASCADE,
    billing_period VARCHAR(24) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    CONSTRAINT uq_service_plan_period UNIQUE (plan_id, billing_period),
    CONSTRAINT ck_service_plan_price CHECK (amount_minor > 0),
    CONSTRAINT ck_service_plan_currency CHECK (currency = UPPER(currency)),
    CONSTRAINT ck_service_plan_period CHECK (billing_period IN (
        'MONTHLY',
        'QUARTERLY',
        'HALF_YEARLY',
        'YEARLY',
        'TWO_YEARLY',
        'THREE_YEARLY',
        'ONETIME'
    ))
);

CREATE TABLE subscription_entitlements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL REFERENCES service_plans(id) ON DELETE RESTRICT,
    plan_name VARCHAR(120) NOT NULL,
    transfer_limit_bytes BIGINT NOT NULL,
    uploaded_bytes BIGINT NOT NULL DEFAULT 0,
    downloaded_bytes BIGINT NOT NULL DEFAULT 0,
    speed_limit_mbps INTEGER,
    device_limit INTEGER,
    reset_policy VARCHAR(32) NOT NULL,
    starts_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE,
    next_reset_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_entitlements_transfer
        CHECK (transfer_limit_bytes > 0),
    CONSTRAINT ck_entitlements_upload
        CHECK (uploaded_bytes >= 0),
    CONSTRAINT ck_entitlements_download
        CHECK (downloaded_bytes >= 0),
    CONSTRAINT ck_entitlements_speed
        CHECK (speed_limit_mbps IS NULL OR speed_limit_mbps > 0),
    CONSTRAINT ck_entitlements_devices
        CHECK (device_limit IS NULL OR device_limit > 0),
    CONSTRAINT ck_entitlements_expiry
        CHECK (expires_at IS NULL OR expires_at > starts_at),
    CONSTRAINT ck_entitlements_reset_policy
        CHECK (reset_policy IN (
            'FIRST_DAY_OF_MONTH',
            'MONTHLY_FROM_ACTIVATION',
            'NEVER',
            'FIRST_DAY_OF_YEAR',
            'YEARLY_FROM_ACTIVATION'
        ))
);

CREATE INDEX idx_entitlements_plan_active
    ON subscription_entitlements (plan_id, expires_at)
    WHERE canceled_at IS NULL;
