CREATE TABLE customer_notifications (
  id VARCHAR(36) PRIMARY KEY,
  action_id VARCHAR(36) NOT NULL UNIQUE,
  customer VARCHAR(64) NOT NULL,
  order_id VARCHAR(36) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(500) NOT NULL,
  created_at VARCHAR(40) NOT NULL,
  read_at VARCHAR(40),
  FOREIGN KEY (action_id) REFERENCES order_actions(id),
  FOREIGN KEY (customer) REFERENCES app_users(username),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE INDEX idx_customer_notifications_inbox ON customer_notifications(customer, created_at);

CREATE TABLE support_requests (
  id VARCHAR(36) PRIMARY KEY,
  customer VARCHAR(64) NOT NULL,
  order_id VARCHAR(36),
  subject VARCHAR(120) NOT NULL,
  message VARCHAR(2000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  created_at VARCHAR(40) NOT NULL,
  FOREIGN KEY (customer) REFERENCES app_users(username),
  FOREIGN KEY (order_id) REFERENCES orders(id)
);
CREATE INDEX idx_support_requests_customer ON support_requests(customer, created_at);
