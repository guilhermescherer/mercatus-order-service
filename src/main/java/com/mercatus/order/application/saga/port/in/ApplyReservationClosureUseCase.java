package com.mercatus.order.application.saga.port.in;

import java.util.UUID;

public interface ApplyReservationClosureUseCase {

    Outcome apply(ReservationClosureCommand command);

    record ReservationClosureCommand(UUID orderId, UUID reservationId, Closure closure) {
    }

    enum Closure {
        RELEASED,
        EXPIRED
    }

    enum Outcome {
        APPLIED,
        IGNORED_UNKNOWN_ORDER,
        IGNORED_UNKNOWN_RESERVATION,
        IGNORED_ALREADY_DECIDED,
        IGNORED_UNEXPECTED
    }
}
