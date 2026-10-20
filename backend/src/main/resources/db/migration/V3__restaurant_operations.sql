ALTER TABLE restaurants ADD COLUMN opening_hours VARCHAR(500) NOT NULL DEFAULT '{}';
ALTER TABLE restaurants ADD COLUMN preparation_minutes INTEGER NOT NULL DEFAULT 25;
ALTER TABLE restaurants ADD COLUMN acceptance_timeout_seconds INTEGER NOT NULL DEFAULT 300;
ALTER TABLE orders ADD COLUMN accept_by VARCHAR(40);
CREATE INDEX idx_orders_acceptance_deadline ON orders(status,accept_by);
