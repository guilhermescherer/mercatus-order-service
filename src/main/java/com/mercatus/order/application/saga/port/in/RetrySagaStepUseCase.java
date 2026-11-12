package com.mercatus.order.application.saga.port.in;

import java.util.UUID;

public interface RetrySagaStepUseCase {

    Outcome retry(UUID orderId);

    enum Outcome {
        RESENT,
        DEADLINE_EXTENDED,
        STALLED,
        NOT_OVERDUE
    }
}
