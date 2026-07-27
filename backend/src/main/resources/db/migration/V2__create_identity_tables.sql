CREATE TABLE roles (
    code VARCHAR(32) PRIMARY KEY,
    description VARCHAR(160) NOT NULL
);

INSERT INTO roles (code, description)
VALUES
    ('USER', 'Standard platform user'),
    ('ADMIN', 'Platform administrator');

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    email_verified_at TIMESTAMP WITH TIME ZONE,
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE UNIQUE INDEX uq_users_email_normalized ON users (LOWER(email));

CREATE TABLE user_roles (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code VARCHAR(32) NOT NULL REFERENCES roles(code),
    PRIMARY KEY (user_id, role_code)
);

CREATE TABLE device_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    session_family_id UUID NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_label VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    replaced_by_id UUID REFERENCES device_sessions(id),
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_device_sessions_user_active
    ON device_sessions (user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX idx_device_sessions_family_active
    ON device_sessions (session_family_id)
    WHERE revoked_at IS NULL;
