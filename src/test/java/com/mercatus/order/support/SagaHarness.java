package com.mercatus.order.support;

import com.mercatus.order.infrastructure.messaging.contract.ContractReader;
import com.mercatus.order.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import static com.mercatus.order.support.OrderFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

public final class SagaHarness implements AutoCloseable {

    public static final String COMMANDS = "inventory.commands.v1";
    public static final String EVENTS = "inventory.events.v1";
    public static final String RESERVE = "inventory.ReserveInventory";
    public static final String RELEASE = "inventory.ReleaseReservation";
    public static final String RESERVED = "inventory.InventoryReserved";
    public static final String FAILED = "inventory.InventoryReservationFailed";
    public static final String RELEASED = "inventory.ReservationReleased";
    public static final String EXPIRED = "inventory.ReservationExpired";

    private final RestClient client;
    private final String bootstrapServers;
    private final ContractReader reader;
    private final KafkaProbe commands;

    public SagaHarness(int port, String bootstrapServers, ContractReader reader) {
        this.client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
        this.bootstrapServers = bootstrapServers;
        this.reader = reader;
        this.commands = new KafkaProbe(bootstrapServers, COMMANDS);
    }

    public OrderResponse create() {
        String key = "saga-" + UUID.randomUUID();
        return client.post().uri("/orders")
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", "flow-" + key)
                .body(request())
                .retrieve()
                .body(OrderResponse.class);
    }

    public OrderResponse get(UUID orderId) {
        return client.get().uri("/orders/" + orderId).retrieve().body(OrderResponse.class);
    }

    public ResponseEntity<OrderResponse> cancel(UUID orderId) {
        return client.post().uri("/orders/" + orderId + "/cancel").retrieve().toEntity(OrderResponse.class);
    }

    public MessageEnvelope awaitCommand(UUID orderId, String type) {
        return awaitCommands(orderId, type, 1).getFirst();
    }

    public List<MessageEnvelope> awaitCommands(UUID orderId, String type, int count) {
        var found = commands.await(record -> matches(record.value(), orderId, type), count, Duration.ofSeconds(30));
        assertThat(found).as("%d %s for order %s", count, type, orderId).hasSizeGreaterThanOrEqualTo(count);
        return found.stream().map(record -> reader.readEnvelope(record.value())).toList();
    }

    public List<MessageEnvelope> commands(UUID orderId, String type, Duration window) {
        return commands.await(record -> matches(record.value(), orderId, type), Integer.MAX_VALUE, window).stream()
                .map(record -> reader.readEnvelope(record.value()))
                .toList();
    }

    public UUID reserved(MessageEnvelope command) {
        UUID reservationId = UUID.randomUUID();
        reply(command, RESERVED, """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-06T21:00:00Z" }
                """.formatted(command.aggregateId(), reservationId));
        return reservationId;
    }

    public void outOfStock(MessageEnvelope command) {
        reply(command, FAILED, """
                { "orderId": "%s", "reason": "OUT_OF_STOCK" }
                """.formatted(command.aggregateId()));
    }

    public void released(MessageEnvelope command, UUID reservationId) {
        reply(command, RELEASED, """
                { "orderId": "%s", "reservationId": "%s" }
                """.formatted(command.aggregateId(), reservationId));
    }

    public void expired(UUID orderId, UUID reservationId) {
        send(UUID.randomUUID(), orderId.toString(), "corr-" + orderId, null, EXPIRED, """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-06T21:00:00Z" }
                """.formatted(orderId, reservationId));
    }

    public void reply(MessageEnvelope command, String type, String payload) {
        send(UUID.randomUUID(), command.aggregateId(), command.correlationId(), command.messageId().toString(), type,
                payload);
    }

    public void send(UUID messageId, String orderId, String correlationId, String causationId, String type,
            String payload) {
        KafkaProbe.send(bootstrapServers, EVENTS, orderId, """
                { "messageId": "%s", "type": "%s", "schemaVersion": 1, "occurredAt": "2026-11-06T20:00:00Z",
                  "aggregateId": "%s", "correlationId": "%s", "causationId": %s, "payload": %s }
                """.formatted(messageId, type, orderId, correlationId,
                causationId == null ? "null" : "\"" + causationId + "\"", payload));
    }

    private boolean matches(String value, UUID orderId, String type) {
        var envelope = reader.readEnvelope(value);
        return orderId.toString().equals(envelope.aggregateId()) && type.equals(envelope.type());
    }

    @Override
    public void close() {
        commands.close();
    }
}
