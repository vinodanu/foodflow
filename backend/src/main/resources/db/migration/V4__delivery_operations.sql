CREATE TABLE partner_availability (
  username VARCHAR(64) PRIMARY KEY,
  available BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (username) REFERENCES app_users(username)
);
CREATE TABLE delivery_offers (
  id VARCHAR(36) PRIMARY KEY,
  order_id VARCHAR(36) NOT NULL,
  partner VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,
  expires_at VARCHAR(40) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  updated_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (order_id) REFERENCES orders(id),
  FOREIGN KEY (partner) REFERENCES app_users(username)
);
CREATE INDEX idx_delivery_offers_expiry ON delivery_offers(status,expires_at);
CREATE INDEX idx_delivery_offers_order ON delivery_offers(order_id,status);
