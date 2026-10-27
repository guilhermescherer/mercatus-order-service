CREATE SEQUENCE order_number_seq START WITH 1;

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    number VARCHAR(32) NOT NULL UNIQUE,
    idempotency_key VARCHAR(128) COLLATE "C" NOT NULL UNIQUE,
    creation_request JSONB NOT NULL,
    customer_snapshot JSONB NOT NULL,
    shipping_address_snapshot JSONB NOT NULL,
    payment_method VARCHAR(16) NOT NULL CHECK (payment_method IN ('CARD', 'PIX')),
    status VARCHAR(32) NOT NULL CHECK (status = 'CREATED'),
    cancellation_reason VARCHAR(64),
    total NUMERIC(19,2) NOT NULL CHECK (total >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'BRL'),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT valid_idempotency_key CHECK (
        length(idempotency_key) > 0 AND idempotency_key = btrim(idempotency_key))
);

CREATE TABLE order_items (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    sku_code TEXT NOT NULL,
    product_name TEXT NOT NULL,
    variation_attributes JSONB NOT NULL,
    quantity BIGINT NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(19,2) NOT NULL CHECK (unit_price >= 0),
    subtotal NUMERIC(19,2) NOT NULL CHECK (subtotal >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'BRL'),
    UNIQUE (order_id, sku_code),
    UNIQUE (order_id, position),
    CHECK (subtotal = unit_price * quantity)
);

CREATE TABLE order_transitions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders(id),
    position INTEGER NOT NULL CHECK (position >= 0),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    trigger VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor VARCHAR(64) NOT NULL,
    UNIQUE (order_id, position)
);
