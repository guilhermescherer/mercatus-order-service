package com.mercatus.order.infrastructure.messaging.outbound;

import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.infrastructure.messaging.config.MessagingProperties;
import com.mercatus.order.infrastructure.messaging.contract.OutboundMessageType;
import com.mercatus.order.infrastructure.messaging.contract.payload.ReleaseReservationPayload;
import com.mercatus.order.infrastructure.messaging.contract.payload.ReserveInventoryPayload;
import com.mercatus.order.infrastructure.messaging.outbox.OutboxStore;
import com.mercatus.order.infrastructure.messaging.outbox.OutboxWriter;
import com.mercatus.order.infrastructure.messaging.outbox.OutboxWriter.OutgoingMessage;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
class InventoryCommandPublisherAdapter implements InventoryCommandPublisher {

    private final OutboxWriter outbox;
    private final OutboxStore store;
    private final MessagingProperties properties;

    InventoryCommandPublisherAdapter(OutboxWriter outbox, OutboxStore store, MessagingProperties properties) {
        this.outbox = outbox;
        this.store = store;
        this.properties = properties;
    }

    @Override
    public UUID requestReservation(Order order, String correlationId) {
        var payload = new ReserveInventoryPayload(order.id(), order.items().stream()
                .map(item -> new ReserveInventoryPayload.Line(item.skuCode(), item.quantity()))
                .toList());

        return send(OutboundMessageType.RESERVE_INVENTORY, order.id(), correlationId, payload);
    }

    @Override
    public UUID requestRelease(UUID orderId, UUID reservationId, String correlationId) {
        return send(OutboundMessageType.RELEASE_RESERVATION, orderId, correlationId, new ReleaseReservationPayload(orderId, reservationId));
    }

    @Override
    public boolean isPending(UUID commandId) {
        return store.isPending(commandId);
    }

    private UUID send(OutboundMessageType type, UUID orderId, String correlationId, Object payload) {
        OutgoingMessage message = new OutgoingMessage(type, orderId.toString(), correlationId, null, payload);
        return outbox.enqueue(properties.topics().commands(), message).messageId();
    }
}
