package com.mercatus.order.infrastructure.messaging;

import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.infrastructure.messaging.OutboxWriter.OutgoingMessage;
import com.mercatus.order.infrastructure.messaging.payload.ReserveInventoryPayload;
import java.util.UUID;
import org.springframework.stereotype.Component;

// Transforma o pedido no comando ReserveInventory e o coloca na outbox.
// Não fala com o Kafka: o OutboxPublisher envia depois do commit do pedido.
// causationId é nulo porque este comando é o primeiro do fluxo (quem o causou foi
// a requisição HTTP, identificada pelo correlationId).
@Component
class InventoryCommandPublisherAdapter implements InventoryCommandPublisher {

    private final OutboxWriter outbox;
    private final MessagingProperties properties;

    InventoryCommandPublisherAdapter(OutboxWriter outbox, MessagingProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Override
    public UUID requestReservation(Order order, String correlationId) {
        var payload = new ReserveInventoryPayload(order.id(), order.items().stream()
                .map(item -> new ReserveInventoryPayload.Line(item.skuCode(), item.quantity()))
                .toList());

        return outbox.enqueue(properties.topics().commands(), new OutgoingMessage(ReserveInventoryPayload.TYPE, 1,
                order.id().toString(), correlationId, null, payload)).messageId();
    }
}
