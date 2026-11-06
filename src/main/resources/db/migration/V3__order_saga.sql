ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('CREATED', 'AWAITING_PAYMENT', 'CANCELLED'));
ALTER TABLE orders ADD CONSTRAINT orders_cancellation_reason_check
    CHECK ((status = 'CANCELLED') = (cancellation_reason IS NOT NULL));

CREATE TABLE order_saga (
    order_id                UUID PRIMARY KEY REFERENCES orders(id),
    step                    VARCHAR(32) NOT NULL CHECK (step IN ('RESERVE')),
    status                  VARCHAR(32) NOT NULL
        CHECK (status IN ('AWAITING_RESERVATION', 'RESERVED', 'CANCELLED')),
    correlation_id          TEXT NOT NULL,
    reserve_command_id      UUID NOT NULL,
    reserve_requested_at    TIMESTAMPTZ NOT NULL,
    reserve_deadline        TIMESTAMPTZ NOT NULL,
    reservation_id          UUID,
    reservation_expires_at  TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    version                 BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT order_saga_reservation CHECK (
        (status = 'RESERVED') = (reservation_id IS NOT NULL AND reservation_expires_at IS NOT NULL))
);

CREATE INDEX idx_order_saga_deadline ON order_saga (status, reserve_deadline);
