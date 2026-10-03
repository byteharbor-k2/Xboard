ALTER TABLE subscription_entitlements
    ADD COLUMN is_trial BOOLEAN NOT NULL DEFAULT FALSE;
