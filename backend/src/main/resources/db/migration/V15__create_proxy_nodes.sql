CREATE TABLE proxy_nodes (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(32) NOT NULL,
    code VARCHAR(64),
    parent_id BIGINT REFERENCES proxy_nodes(id) ON DELETE SET NULL,
    machine_id BIGINT REFERENCES node_machines(id) ON DELETE SET NULL,
    group_ids TEXT NOT NULL DEFAULT '[]',
    route_ids TEXT NOT NULL DEFAULT '[]',
    name VARCHAR(255) NOT NULL,
    rate NUMERIC(10, 4) NOT NULL DEFAULT 1,
    rate_time_enable BOOLEAN NOT NULL DEFAULT FALSE,
    rate_time_ranges TEXT NOT NULL DEFAULT '[]',
    transfer_enable BIGINT NOT NULL DEFAULT 0,
    upload_bytes BIGINT NOT NULL DEFAULT 0,
    download_bytes BIGINT NOT NULL DEFAULT 0,
    tags TEXT NOT NULL DEFAULT '[]',
    host VARCHAR(255),
    port INTEGER,
    server_port INTEGER NOT NULL,
    protocol_settings TEXT NOT NULL DEFAULT '{}',
    custom_outbounds TEXT NOT NULL DEFAULT '[]',
    custom_routes TEXT NOT NULL DEFAULT '[]',
    cert_config TEXT,
    is_show BOOLEAN NOT NULL DEFAULT TRUE,
    is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    online_users INTEGER NOT NULL DEFAULT 0,
    online_connections INTEGER NOT NULL DEFAULT 0,
    load_status TEXT,
    metrics TEXT,
    last_check_at TIMESTAMP WITH TIME ZONE,
    last_push_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_proxy_nodes_machine_enabled
    ON proxy_nodes (machine_id, is_enabled, sort_order, id);

CREATE INDEX idx_proxy_nodes_sort
    ON proxy_nodes (sort_order, id);
