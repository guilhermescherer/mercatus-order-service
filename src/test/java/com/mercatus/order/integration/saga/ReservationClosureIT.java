package com.mercatus.order.integration.saga;

import com.mercatus.order.infrastructure.messaging.contract.ContractReader;
import com.mercatus.order.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.order.support.KafkaProbe;
import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.support.SagaHarness;
import com.mercatus.order.web.order.dto.OrderResponse;
import com.mercatus.order.web.order.dto.OrderTransitionResponse;
import java.time.Duration;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static com.mercatus.order.support.SagaHarness.RELEASE;
import static com.mercatus.order.support.SagaHarness.RESERVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "order.messaging.outbox.poll-interval-ms=200",
                "order.saga.recovery.interval-ms=3600000"
        })
@DirtiesContext
class ReservationClosureIT {

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

    private SagaHarness saga;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("order.clients.catalog.base-url", remote::baseUrl);
        registry.add("order.clients.customer.base-url", remote::baseUrl);
    }

    @BeforeAll
    static void createTopics() {
        KafkaProbe.createTopics(kafka.getBootstrapServers(), SagaHarness.COMMANDS, SagaHarness.EVENTS);
    }

    @AfterAll
    static void closeRemote() {
        remote.close();
    }

    @BeforeEach
    void setUp() {
        remote.reset();
        saga = new SagaHarness(port, kafka.getBootstrapServers(), reader);
    }

    @AfterEach
    void tearDown() {
        saga.close();
    }

    @Test
    void expiredReservationCancelsAnOrderAwaitingPaymentWithoutAskingForRelease() {
        OrderResponse created = saga.create();
        UUID reservationId = saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");

        saga.expired(created.id(), reservationId);

        OrderResponse cancelled = awaitStatus(created.id(), "CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("RESERVATION_EXPIRED");
        assertThat(cancelled.history()).extracting(OrderTransitionResponse::toStatus)
                .containsExactly("CREATED", "AWAITING_PAYMENT", "CANCELLING", "CANCELLED");
        assertThat(cancelled.history().subList(2, 4)).extracting(OrderTransitionResponse::trigger)
                .containsOnly("RESERVATION_EXPIRED");
        assertThat(saga.commands(created.id(), RELEASE, Duration.ofSeconds(2))).isEmpty();
        assertThat(sagaStatus(created.id())).isEqualTo("CANCELLED");
    }

    @Test
    void repeatedClosuresChangeTheOrderOnce() {
        OrderResponse created = saga.create();
        UUID reservationId = saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");
        UUID messageId = UUID.randomUUID();
        String payload = """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-06T21:00:00Z" }
                """.formatted(created.id(), reservationId);

        saga.send(messageId, created.id().toString(), "corr", null, SagaHarness.EXPIRED, payload);
        saga.send(messageId, created.id().toString(), "corr", null, SagaHarness.EXPIRED, payload);
        saga.expired(created.id(), reservationId);

        awaitStatus(created.id(), "CANCELLED");
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> saga.get(created.id()).history().size() == 4);
    }

    @Test
    void expiryWhileWaitingForReleaseFinishesTheCustomerCancellation() {
        OrderResponse created = saga.create();
        UUID reservationId = saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");
        saga.cancel(created.id());
        MessageEnvelope release = saga.awaitCommand(created.id(), RELEASE);

        saga.expired(created.id(), reservationId);

        OrderResponse cancelled = awaitStatus(created.id(), "CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelled.history().getLast().trigger()).isEqualTo("RESERVATION_EXPIRED");

        saga.reply(release, SagaHarness.EXPIRED, """
                { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-11-06T21:00:00Z" }
                """.formatted(created.id(), reservationId));
        saga.released(release, reservationId);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> saga.get(created.id()).history().size() == 4);
    }

    @Test
    void closureForAnotherReservationIsIgnored() {
        OrderResponse created = saga.create();
        saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");

        saga.expired(created.id(), UUID.randomUUID());

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> saga.get(created.id()).status().equals("AWAITING_PAYMENT"));
    }

    @Test
    void lateReservationForACancelledOrderIsReleasedWithoutReopeningIt() {
        OrderResponse created = saga.create();
        MessageEnvelope reserve = saga.awaitCommand(created.id(), RESERVE);
        saga.outOfStock(reserve);
        awaitStatus(created.id(), "CANCELLED");

        UUID orphan = saga.reserved(reserve);

        MessageEnvelope release = saga.awaitCommand(created.id(), RELEASE);
        assertThat(release.payload().get("reservationId").asString()).isEqualTo(orphan.toString());
        OrderResponse order = saga.get(created.id());
        assertThat(order.status()).isEqualTo("CANCELLED");
        assertThat(order.cancellationReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(order.history()).hasSize(2);
    }

    private OrderResponse awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).until(() -> saga.get(orderId).status().equals(status));
        return saga.get(orderId);
    }

    private String sagaStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM order_saga WHERE order_id = ?", String.class, orderId);
    }
}
