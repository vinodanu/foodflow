CREATE TABLE payments (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(36) NOT NULL,
  provider_reference VARCHAR(80) NOT NULL UNIQUE,
  amount DECIMAL(12,2) NOT NULL,
  currency VARCHAR(3) NOT NULL,
  status VARCHAR(24) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(id),
  UNIQUE(order_id,idempotency_key)
);
CREATE TABLE payment_events (
  event_id VARCHAR(100) PRIMARY KEY,
  payment_id VARCHAR(36) NOT NULL,
  payload_hash VARCHAR(64) NOT NULL,
  outcome VARCHAR(24) NOT NULL,
  received_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (payment_id) REFERENCES payments(id)
);
CREATE TABLE refunds (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(36) NOT NULL UNIQUE,
  payment_id VARCHAR(36) NOT NULL,
  amount DECIMAL(12,2) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (payment_id) REFERENCES payments(id)
);
