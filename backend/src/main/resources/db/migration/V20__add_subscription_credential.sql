-- The subscription credential.
--
-- This is not the user's node identity. `users.node_user_id` (and the account
-- UUID nodes know as the user's `uuid`) authenticate a user *to a node*; this
-- token authenticates a user to the panel's own subscription entry point,
-- which is the only thing it unlocks: a client configuration for the nodes
-- their entitlement already allows.
--
-- Held in the clear on purpose. It is a capability URL, and the customer's
-- own account page has to be able to show it at any time so they can paste it
-- into a client - hashing it would reduce that to "visible once, right after
-- a reset". The original panel keeps its equivalent in a plain column too.

ALTER TABLE users ADD COLUMN subscription_token VARCHAR(64);

-- Accounts that predate this column still need a link that works. md5 over
-- per-row randomness is the same order of unpredictability as the original
-- panel's md5(uuid . '-' . time()); anything generated from here on comes
-- from SecureRandom in Java instead.
UPDATE users
SET subscription_token = md5(random()::text || clock_timestamp()::text || id::text);

ALTER TABLE users ALTER COLUMN subscription_token SET NOT NULL;

CREATE UNIQUE INDEX uq_users_subscription_token ON users (subscription_token);
