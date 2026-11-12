ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('CREATED', 'AWAITING_PAYMENT', 'CANCELLING', 'CANCELLED'));
ALTER TABLE orders DROP CONSTRAINT orders_cancellation_reason_check;
ALTER TABLE orders ADD CONSTRAINT orders_cancellation_reason_check
    CHECK ((status IN ('CANCELLING', 'CANCELLED')) = (cancellation_reason IS NOT NULL));

ALTER TABLE order_saga ADD COLUMN release_command_id UUID;
ALTER TABLE order_saga ADD COLUMN release_requested_at TIMESTAMPTZ;
ALTER TABLE order_saga ADD COLUMN release_deadline TIMESTAMPTZ;

ALTER TABLE order_saga DROP CONSTRAINT order_saga_step_check;
ALTER TABLE order_saga ADD CONSTRAINT order_saga_step_check CHECK (step IN ('RESERVE', 'RELEASE'));
ALTER TABLE order_saga DROP CONSTRAINT order_saga_status_check;
ALTER TABLE order_saga ADD CONSTRAINT order_saga_status_check
    CHECK (status IN ('AWAITING_RESERVATION', 'RESERVED', 'AWAITING_RELEASE', 'CANCELLED'));
ALTER TABLE order_saga DROP CONSTRAINT order_saga_reservation;
ALTER TABLE order_saga ADD CONSTRAINT order_saga_reservation CHECK (
    (reservation_id IS NULL) = (reservation_expires_at IS NULL)
    AND (status NOT IN ('RESERVED', 'AWAITING_RELEASE') OR reservation_id IS NOT NULL)
    AND (status <> 'AWAITING_RESERVATION' OR reservation_id IS NULL));
ALTER TABLE order_saga ADD CONSTRAINT order_saga_release CHECK (
    (release_command_id IS NULL) = (release_requested_at IS NULL)
    AND (release_command_id IS NULL) = (release_deadline IS NULL)
    AND (status <> 'AWAITING_RELEASE' OR release_command_id IS NOT NULL));

CREATE INDEX idx_order_saga_release_deadline ON order_saga (status, release_deadline);

ALTER TABLE order_saga ADD COLUMN retries INTEGER NOT NULL DEFAULT 0 CHECK (retries >= 0);
ALTER TABLE order_saga ADD COLUMN stalled_at TIMESTAMPTZ;
