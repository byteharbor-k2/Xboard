-- Keep the account primary key stable while allowing customers to replace the
-- UUID that xboard-node uses for proxy authentication.
ALTER TABLE users ADD COLUMN proxy_uuid UUID;

-- Existing node configurations keep working until their owner explicitly
-- resets credentials. New registrations also begin with this compatible value.
UPDATE users SET proxy_uuid = id;
