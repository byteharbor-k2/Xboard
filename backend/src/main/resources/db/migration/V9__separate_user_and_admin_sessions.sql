ALTER TABLE device_sessions
    ADD COLUMN session_scope VARCHAR(16) NOT NULL DEFAULT 'USER';

ALTER TABLE device_sessions
    ADD CONSTRAINT ck_device_sessions_scope
    CHECK (session_scope IN ('USER', 'ADMIN'));

DROP INDEX IF EXISTS idx_device_sessions_user_active;

CREATE INDEX idx_device_sessions_user_scope_active
    ON device_sessions (user_id, session_scope, expires_at)
    WHERE revoked_at IS NULL;
