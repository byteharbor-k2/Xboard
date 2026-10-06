-- Periodic plans that used the old default inherit the global reset policy.
-- Other plan policies remain explicit overrides; entitlement rows are snapshots.
ALTER TABLE service_plans
    ALTER COLUMN reset_policy DROP NOT NULL;

UPDATE service_plans
SET reset_policy = NULL
WHERE reset_policy = 'MONTHLY_FROM_ACTIVATION'
  AND plan_type = 'SUBSCRIPTION';

-- Traffic packages never reset automatically, regardless of global settings.
UPDATE service_plans
SET reset_policy = 'NEVER'
WHERE plan_type = 'TRAFFIC_PACKAGE';

UPDATE subscription_entitlements
SET reset_policy = 'NEVER', next_reset_at = NULL
WHERE is_trial = TRUE;
