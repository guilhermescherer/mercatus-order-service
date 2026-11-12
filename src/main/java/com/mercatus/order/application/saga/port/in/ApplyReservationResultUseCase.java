package com.mercatus.order.application.saga.port.in;

import java.time.Instant;
import java.util.UUID;

public interface ApplyReservationResultUseCase {

    Outcome apply(ReservationResultCommand command);

    record ReservationResultCommand(UUID orderId, Result result, UUID reservationId, Instant expiresAt) {

        public static ReservationResultCommand reserved(UUID orderId, UUID reservationId, Instant expiresAt) {
            return new ReservationResultCommand(orderId, Result.RESERVED, reservationId, expiresAt);
        }

        public static ReservationResultCommand outOfStock(UUID orderId) {
            return new ReservationResultCommand(orderId, Result.OUT_OF_STOCK, null, null);
        }
    }

    enum Result {
        RESERVED,
        OUT_OF_STOCK
    }

    enum Outcome {
        APPLIED,
        RELEASE_REQUESTED,
        IGNORED_UNKNOWN_ORDER,
        IGNORED_ALREADY_DECIDED
    }
}
