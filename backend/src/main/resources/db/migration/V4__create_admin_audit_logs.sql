CREATE TABLE admin_audit_logs (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(120) NOT NULL,
    http_method VARCHAR(12) NOT NULL,
    request_path VARCHAR(255) NOT NULL,
    response_status INTEGER NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    duration_ms BIGINT NOT NULL,
    request_id VARCHAR(128),
    ip_address VARCHAR(45),
    user_agent VARCHAR(512),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_admin_audit_logs_occurred_at
    ON admin_audit_logs (occurred_at DESC);

CREATE INDEX idx_admin_audit_logs_actor_occurred
    ON admin_audit_logs (actor_id, occurred_at DESC);
