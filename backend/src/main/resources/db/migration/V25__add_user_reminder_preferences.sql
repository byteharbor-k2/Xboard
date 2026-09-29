-- Per-account reminder mail switches.
--
-- The original panel kept remind_expire / remind_traffic on its users table
-- and only its daily remind mail sweep read them; the admin-side gate
-- email.remind_mail_enable is already a platform setting. Both default to on,
-- like the original's default 1, so an account opts out rather than in.
ALTER TABLE users ADD COLUMN remind_expire BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE users ADD COLUMN remind_traffic BOOLEAN NOT NULL DEFAULT TRUE;
