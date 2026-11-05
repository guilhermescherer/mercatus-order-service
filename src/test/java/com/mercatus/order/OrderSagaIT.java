package com.mercatus.order;

import com.mercatus.order.infrastructure.messaging.ContractReader;
import com.mercatus.order.infrastructure.messaging.MessageEnvelope;
import com.mercatus.order.support.KafkaProbe;
import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static com.mercatus.order.support.OrderFixture.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "order.messaging.outbox.poll-interval-ms=200")
@DirtiesContext
class OrderSagaIT {

    private static final String COMMANDS = "inventory.commands.v1";
    private static final String EVENTS = "inventory.events.v1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    static final RemoteStub remote = new RemoteStub();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContractReader reader;

    private RestClient client;
    private KafkaProbe commands;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("order.clients.catalog.base-url", remote::baseUrl);
        registry.add("order.clients.customer.base-url", remote::baseUrl);
    }

    @BeforeAll
    static void createTopics() {
        KafkaProbe.createTopics(kafka.getBootstrapServers(), COMMANDS, EVENTS);
    }

    @AfterAll
    static void closeRemote() {
        remote.close();
    }

    @BeforeEach
    void setUp() {
        remote.reset();
        client = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1").build();
        commands = new KafkaProbe(kafka.getBootstrapServers(), COMMANDS);
    }

    @AfterEach
    void tearDown() {
        commands.close();
    }

    @Test
    void newOrderPublishesOneReserveCommandAndReplayPublishesNothing() {
        String key = "saga-" + UUID.randomUUID();
        OrderResponse created = create(key, "flow-" + key);
        create(key, "flow-" + key);

        MessageEnvelope command = awaitCommand(created.id());
        assertThat(command.type()).isEqualTo("inventory.ReserveInventory");
        assertThat(command.correlationId()).isEqualTo("flow-" + key);
        assertThat(command.causationId()).isNull();
        assertThat(command.payload().get("orderId").asString()).isEqualTo(created.id().toString());
        assertThat(command.payload().get("lines")).hasSize(2);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> commandsFor(created.id()) == 1);
        assertThat(sagaStatus(created.id())).isEqualTo("AWAITING_RESERVATION");
        assertThat(get(created.id()).status()).isEqualTo("CREATED");
    }

    @Test
    void reservedReplyMovesOrderToAwaitingPayment() {
        OrderResponse created = create("saga-" + UUID.randomUUID(), "flow-reserved");
        MessageEnvelope command = awaitCommand(created.id());
        UUID reservationId = UUID.randomUUID();

        reply(UUID.randomUUID(), command, "inventory.InventoryReserved", """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-04T21:00:00Z" }
                """.formatted(created.id(), reservationId));

        await().atMost(Duration.ofSeconds(30)).until(() -> get(created.id()).status().equals("AWAITING_PAYMENT"));
        var order = get(created.id());
        assertThat(order.cancellationReason()).isNull();
        assertThat(order.history()).hasSize(2);
        assertThat(order.history().getLast().trigger()).isEqualTo("INVENTORY_RESERVED");
        assertThat(sagaStatus(created.id())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT reservation_id FROM order_saga WHERE order_id = ?", UUID.class,
                created.id())).isEqualTo(reservationId);
    }

    @Test
    void outOfStockReplyCancelsTheOrder() {
        OrderResponse created = create("saga-" + UUID.randomUUID(), "flow-out-of-stock");
        MessageEnvelope command = awaitCommand(created.id());

        reply(UUID.randomUUID(), command, "inventory.InventoryReservationFailed", """
                { "orderId": "%s", "reason": "OUT_OF_STOCK" }
                """.formatted(created.id()));

        await().atMost(Duration.ofSeconds(30)).until(() -> get(created.id()).status().equals("CANCELLED"));
        assertThat(get(created.id()).cancellationReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(sagaStatus(created.id())).isEqualTo("CANCELLED");
    }

    @Test
    void repeatedAndLateRepliesHaveNoEffect() {
        OrderResponse created = create("saga-" + UUID.randomUUID(), "flow-late");
        MessageEnvelope command = awaitCommand(created.id());
        UUID replyId = UUID.randomUUID();
        String reserved = """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-04T21:00:00Z" }
                """.formatted(created.id(), UUID.randomUUID());

        reply(replyId, command, "inventory.InventoryReserved", reserved);
        reply(replyId, command, "inventory.InventoryReserved", reserved);
        reply(UUID.randomUUID(), command, "inventory.InventoryReservationFailed", """
                { "orderId": "%s", "reason": "OUT_OF_STOCK" }
                """.formatted(created.id()));

        await().atMost(Duration.ofSeconds(30)).until(() -> get(created.id()).status().equals("AWAITING_PAYMENT"));
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> get(created.id()).history().size() == 2);
        assertThat(get(created.id()).status()).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM inbox_message WHERE message_id = ?", Long.class,
                replyId)).isEqualTo(1);
    }

    @Test
    void replyForUnknownOrderIsIgnoredWithoutBlockingTheNextOne() {
        OrderResponse created = create("saga-" + UUID.randomUUID(), "flow-unknown");
        MessageEnvelope command = awaitCommand(created.id());
        UUID unknown = UUID.randomUUID();

        KafkaProbe.send(kafka.getBootstrapServers(), EVENTS, unknown.toString(), envelope(UUID.randomUUID(),
                "inventory.InventoryReservationFailed", unknown.toString(), "flow-x", null,
                "{ \"orderId\": \"%s\", \"reason\": \"OUT_OF_STOCK\" }".formatted(unknown)));
        reply(UUID.randomUUID(), command, "inventory.InventoryReservationFailed", """
                { "orderId": "%s", "reason": "OUT_OF_STOCK" }
                """.formatted(created.id()));

        await().atMost(Duration.ofSeconds(30)).until(() -> get(created.id()).status().equals("CANCELLED"));
    }

    private OrderResponse create(String key, String correlationId) {
        return client.post().uri("/orders")
                .header("Idempotency-Key", key)
                .header("X-Correlation-Id", correlationId)
                .body(request())
                .retrieve()
                .body(OrderResponse.class);
    }

    private OrderResponse get(UUID id) {
        return client.get().uri("/orders/" + id).retrieve().body(OrderResponse.class);
    }

    private MessageEnvelope awaitCommand(UUID orderId) {
        var found = commands.await(record -> isCommandFor(record, orderId), 1, Duration.ofSeconds(30));
        assertThat(found).as("ReserveInventory for order %s", orderId).isNotEmpty();
        assertThat(found.getFirst().key()).isEqualTo(orderId.toString());
        return reader.readEnvelope(found.getFirst().value());
    }

    private long commandsFor(UUID orderId) {
        return commands.await(record -> isCommandFor(record, orderId), Integer.MAX_VALUE, Duration.ofMillis(300))
                .size();
    }

    private boolean isCommandFor(ConsumerRecord<String, String> record, UUID orderId) {
        return orderId.toString().equals(reader.readEnvelope(record.value()).aggregateId());
    }

    private void reply(UUID messageId, MessageEnvelope command, String type, String payload) {
        KafkaProbe.send(kafka.getBootstrapServers(), EVENTS, command.aggregateId(), envelope(messageId, type,
                command.aggregateId(), command.correlationId(), command.messageId().toString(), payload));
    }

    private String envelope(UUID messageId, String type, String aggregateId, String correlationId,
            String causationId, String payload) {
        return """
                { "messageId": "%s", "type": "%s", "schemaVersion": 1, "occurredAt": "2026-11-04T20:00:00Z",
                  "aggregateId": "%s", "correlationId": "%s", "causationId": %s, "payload": %s }
                """.formatted(messageId, type, aggregateId, correlationId,
                causationId == null ? "null" : "\"" + causationId + "\"", payload);
    }

    private String sagaStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM order_saga WHERE order_id = ?", String.class, orderId);
    }
}
