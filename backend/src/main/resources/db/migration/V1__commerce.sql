CREATE TABLE app_users
(
    username      VARCHAR(64) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    restaurant_id VARCHAR(64)
);
CREATE TABLE restaurants
(
    id              VARCHAR(64) PRIMARY KEY,
    content         TEXT    NOT NULL,
    open_for_orders BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE dishes
(
    id            VARCHAR(64) PRIMARY KEY,
    restaurant_id VARCHAR(64)    NOT NULL,
    name          VARCHAR(200)   NOT NULL,
    price         DECIMAL(12, 2) NOT NULL,
    available     BOOLEAN        NOT NULL DEFAULT TRUE,
    FOREIGN KEY (restaurant_id) REFERENCES restaurants (id)
);
CREATE TABLE orders
(
    id              VARCHAR(36) PRIMARY KEY,
    customer        VARCHAR(64)    NOT NULL,
    restaurant_id   VARCHAR(64)    NOT NULL,
    partner         VARCHAR(64),
    status          VARCHAR(30)    NOT NULL,
    payment_status  VARCHAR(30)    NOT NULL,
    refund_status   VARCHAR(30)    NOT NULL,
    subtotal        DECIMAL(12, 2) NOT NULL,
    delivery_fee    DECIMAL(12, 2) NOT NULL,
    total           DECIMAL(12, 2) NOT NULL,
    address         VARCHAR(500)   NOT NULL,
    area            VARCHAR(100)   NOT NULL,
    created_at      VARCHAR(40)    NOT NULL,
    version         BIGINT         NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(100)   NOT NULL,
    request_hash    VARCHAR(64)    NOT NULL,
    FOREIGN KEY (customer) REFERENCES app_users (username),
    FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    UNIQUE (customer, idempotency_key)
);
CREATE TABLE order_items
(
    order_id   VARCHAR(36)    NOT NULL,
    dish_id    VARCHAR(64)    NOT NULL,
    name       VARCHAR(200)   NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    quantity   INTEGER        NOT NULL,
    PRIMARY KEY (order_id, dish_id),
    FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE TABLE order_actions
(
    id          VARCHAR(36) PRIMARY KEY,
    order_id    VARCHAR(36)  NOT NULL,
    actor       VARCHAR(64)  NOT NULL,
    action      VARCHAR(40)  NOT NULL,
    occurred_at VARCHAR(40)  NOT NULL,
    note        VARCHAR(500) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders (id)
);
CREATE TABLE outbox
(
    event_id     VARCHAR(36) PRIMARY KEY,
    aggregate_id VARCHAR(36) NOT NULL,
    event_type   VARCHAR(80) NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   VARCHAR(40) NOT NULL,
    published    BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_orders_customer ON orders (customer, created_at);
CREATE INDEX idx_orders_restaurant ON orders (restaurant_id, status);
