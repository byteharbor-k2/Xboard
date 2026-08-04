CREATE TABLE node_machines (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    token VARCHAR(64) NOT NULL UNIQUE,
    notes TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    last_seen_at TIMESTAMP WITH TIME ZONE,
    load_status TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE node_machine_load_history (
    id BIGSERIAL PRIMARY KEY,
    machine_id BIGINT NOT NULL REFERENCES node_machines(id) ON DELETE CASCADE,
    cpu DOUBLE PRECISION NOT NULL DEFAULT 0,
    mem_total BIGINT NOT NULL DEFAULT 0,
    mem_used BIGINT NOT NULL DEFAULT 0,
    disk_total BIGINT NOT NULL DEFAULT 0,
    disk_used BIGINT NOT NULL DEFAULT 0,
    net_in_speed DOUBLE PRECISION,
    net_out_speed DOUBLE PRECISION,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_node_machine_history_recorded
    ON node_machine_load_history (machine_id, recorded_at DESC);
