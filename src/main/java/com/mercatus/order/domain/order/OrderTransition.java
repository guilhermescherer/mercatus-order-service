package com.mercatus.order.domain.order;

import java.time.Instant;

public record OrderTransition(OrderStatus fromStatus, OrderStatus toStatus, TransitionTrigger trigger,
        Instant occurredAt, TransitionActor actor) {

    public static OrderTransition initial(Instant now) {
        return new OrderTransition(null, OrderStatus.CREATED, TransitionTrigger.ORDER_CREATED, now,
                TransitionActor.HTTP_REQUEST);
    }
}
