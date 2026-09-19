-- Fields the administrator edits on an account that nothing else owns.
--
-- The traffic allowance deliberately does not live here: it belongs to the
-- account's subscription entitlement, which already carries the allowance, the
-- used counters and the plan they came from. Keeping a second copy on the
-- account would mean two answers to "how much does this customer have left",
-- and the panel would have to keep them in step on every purchase.
--
-- Banning likewise needs no column: users.status already carries ACTIVE and
-- SUSPENDED, and both the sign-in path and the subscription endpoint already
-- refuse a suspended account.
ALTER TABLE users ADD COLUMN remarks TEXT;

-- An operator's per-account speed cap. Null means the plan's own limit applies,
-- which is the normal case; this exists for the exceptions.
ALTER TABLE users ADD COLUMN speed_limit_mbps INTEGER;

ALTER TABLE users
    ADD CONSTRAINT ck_users_speed_limit
        CHECK (speed_limit_mbps IS NULL OR speed_limit_mbps > 0);
