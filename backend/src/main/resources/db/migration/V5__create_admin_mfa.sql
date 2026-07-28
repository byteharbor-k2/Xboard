CREATE TABLE admin_mfa_methods (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    encrypted_secret TEXT NOT NULL,
    enabled_at TIMESTAMPTZ,
    last_used_time_step BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE admin_mfa_recovery_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash VARCHAR(64) NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_admin_mfa_recovery_code_hash
    ON admin_mfa_recovery_codes (user_id, code_hash);

CREATE INDEX idx_admin_mfa_recovery_codes_active
    ON admin_mfa_recovery_codes (user_id)
    WHERE used_at IS NULL;
