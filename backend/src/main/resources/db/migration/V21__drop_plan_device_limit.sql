-- Multi-device access is not treated as a vulnerability, and a per-plan device
-- ceiling could not be enforced reliably: whether one customer shows up as one
-- device or several depends on how their network is routed (dual-stack IPv6,
-- mobile handover, load spread across nodes), which the panel does not control.
-- The ceiling is therefore gone.
--
-- Online-device data keeps being collected and is still shown to administrators.
-- Only the limit is dropped, not the observation.
ALTER TABLE service_plans
    DROP CONSTRAINT ck_service_plans_devices,
    DROP COLUMN device_limit;

ALTER TABLE subscription_entitlements
    DROP CONSTRAINT ck_entitlements_devices,
    DROP COLUMN device_limit;
