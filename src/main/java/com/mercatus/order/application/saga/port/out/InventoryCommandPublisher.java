package com.mercatus.order.application.saga.port.out;

import com.mercatus.order.domain.order.Order;
import java.util.UUID;

public interface InventoryCommandPublisher {

    UUID requestReservation(Order order, String correlationId);

    UUID requestRelease(UUID orderId, UUID reservationId, String correlationId);

    boolean isPending(UUID commandId);
}
