package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.order.OrderTransition;
import java.time.Instant;

public record OrderTransitionResponse(String fromStatus, String toStatus, String trigger, Instant occurredAt, String actor) {

    static OrderTransitionResponse from(OrderTransition transition) {
        return new OrderTransitionResponse(transition.fromStatus() == null ? null : transition.fromStatus().name(),
                transition.toStatus().name(), transition.trigger(), transition.occurredAt(), transition.actor());
    }
}
