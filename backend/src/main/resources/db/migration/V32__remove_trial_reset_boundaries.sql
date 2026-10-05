UPDATE subscription_entitlements
SET next_reset_at = NULL
WHERE is_trial = TRUE;
