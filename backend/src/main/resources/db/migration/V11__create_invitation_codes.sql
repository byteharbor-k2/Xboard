ALTER TABLE users
    ADD COLUMN inviter_user_id UUID REFERENCES users(id) ON DELETE SET NULL;

CREATE INDEX idx_users_inviter ON users (inviter_user_id);

CREATE TABLE invite_codes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code VARCHAR(32) NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_invite_codes_normalized
    ON invite_codes (LOWER(code));

CREATE INDEX idx_invite_codes_user_available
    ON invite_codes (user_id, created_at)
    WHERE used_at IS NULL;
