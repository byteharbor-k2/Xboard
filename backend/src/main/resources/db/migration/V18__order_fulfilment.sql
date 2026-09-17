-- Settlement and provisioning of orders.
--
-- An order is settled once and provisioned once, so the payment reference is
-- kept with it: a repeated callback must not hand out a second subscription.
ALTER TABLE orders ADD COLUMN callback_no VARCHAR(64);

-- The sweep that recycles orders nobody paid for scans by age across the two
-- open statuses, so index exactly that.
CREATE INDEX idx_orders_open_created_at
    ON orders (created_at)
    WHERE status IN ('PENDING', 'PROCESSING');
