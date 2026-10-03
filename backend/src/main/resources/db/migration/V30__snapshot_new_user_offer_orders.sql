-- Keep the offer classification attached to the order that was created under
-- the then-current configuration, even if the administrator later changes it.
ALTER TABLE orders
    ADD COLUMN is_new_user_offer BOOLEAN NOT NULL DEFAULT FALSE;
