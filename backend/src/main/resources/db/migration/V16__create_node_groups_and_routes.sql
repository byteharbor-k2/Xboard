CREATE TABLE node_access_groups (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_node_access_groups_name_normalized
    ON node_access_groups (LOWER(name));

CREATE TABLE node_route_rules (
    id BIGSERIAL PRIMARY KEY,
    remarks VARCHAR(255) NOT NULL,
    match_rules TEXT NOT NULL DEFAULT '[]',
    action VARCHAR(16) NOT NULL,
    action_value VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_node_route_rules_action
        CHECK (action IN ('block', 'direct', 'dns', 'proxy'))
);

ALTER TABLE service_plans
    ADD COLUMN server_group_id BIGINT
        REFERENCES node_access_groups(id) ON DELETE RESTRICT;

CREATE INDEX idx_service_plans_server_group
    ON service_plans (server_group_id);

ALTER TABLE users
    ADD COLUMN server_group_id BIGINT
        REFERENCES node_access_groups(id) ON DELETE RESTRICT;

CREATE INDEX idx_users_server_group
    ON users (server_group_id);

CREATE SEQUENCE user_node_identity_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE users
    ADD COLUMN node_user_id BIGINT;

UPDATE users
SET node_user_id = nextval('user_node_identity_seq');

ALTER TABLE users
    ALTER COLUMN node_user_id SET DEFAULT nextval('user_node_identity_seq'),
    ALTER COLUMN node_user_id SET NOT NULL;

CREATE UNIQUE INDEX uq_users_node_user_id
    ON users (node_user_id);
