CREATE TABLE platform_settings (
    setting_key VARCHAR(160) PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
