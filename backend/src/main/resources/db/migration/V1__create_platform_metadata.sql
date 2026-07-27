CREATE TABLE platform_metadata (
    metadata_key VARCHAR(100) PRIMARY KEY,
    metadata_value TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_metadata (metadata_key, metadata_value)
VALUES ('schema_version', '1');
