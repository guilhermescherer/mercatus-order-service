package com.mercatus.order.domain.order;

import java.time.Instant;

public record OrderTransition(OrderStatus fromStatus, OrderStatus toStatus, String trigger,
        Instant occurredAt, String actor) {

    public static OrderTransition initial(Instant now) {
        return new OrderTransition(null, OrderStatus.CREATED, "ORDER_CREATED", now, "HTTP_REQUEST");
    }
}
