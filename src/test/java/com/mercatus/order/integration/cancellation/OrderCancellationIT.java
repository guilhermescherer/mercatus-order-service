package com.mercatus.order.integration.cancellation;

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
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static com.mercatus.order.support.SagaHarness.RELEASE;
import static com.mercatus.order.support.SagaHarness.RESERVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "order.messaging.outbox.poll-interval-ms=200",
                "order.saga.recovery.interval-ms=3600000"
        })
@DirtiesContext
class OrderCancellationIT {

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
    void cancellingAnOrderAwaitingPaymentReleasesTheReservationBeforeCancelling() {
        OrderResponse created = saga.create();
        UUID reservationId = saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");

        var response = saga.cancel(created.id());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().status()).isEqualTo("CANCELLING");
        assertThat(response.getBody().cancellationReason()).isEqualTo("CUSTOMER_REQUEST");

        MessageEnvelope release = saga.awaitCommand(created.id(), RELEASE);
        assertThat(release.correlationId()).isEqualTo(correlation(created.id()));
        assertThat(release.payload().get("reservationId").asString()).isEqualTo(reservationId.toString());
        assertThat(sagaStatus(created.id())).isEqualTo("AWAITING_RELEASE");

        saga.released(release, reservationId);

        OrderResponse cancelled = awaitStatus(created.id(), "CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelled.history()).extracting(OrderTransitionResponse::toStatus)
                .containsExactly("CREATED", "AWAITING_PAYMENT", "CANCELLING", "CANCELLED");
        assertThat(cancelled.history()).extracting(OrderTransitionResponse::trigger)
                .containsExactly("ORDER_CREATED", "INVENTORY_RESERVED", "CUSTOMER_CANCELLATION_REQUESTED",
                        "RESERVATION_RELEASED");
        assertThat(cancelled.history().get(2).actor()).isEqualTo("CUSTOMER");
        assertThat(sagaStatus(created.id())).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingDuringTheReservationWaitsAndReleasesALateReservation() {
        OrderResponse created = saga.create();
        MessageEnvelope reserve = saga.awaitCommand(created.id(), RESERVE);

        assertThat(saga.cancel(created.id()).getStatusCode().value()).isEqualTo(202);
        assertThat(saga.commands(created.id(), RELEASE, Duration.ofSeconds(2))).isEmpty();

        UUID reservationId = saga.reserved(reserve);

        MessageEnvelope release = saga.awaitCommand(created.id(), RELEASE);
        assertThat(release.payload().get("reservationId").asString()).isEqualTo(reservationId.toString());
        assertThat(saga.get(created.id()).status()).isEqualTo("CANCELLING");

        saga.released(release, reservationId);

        OrderResponse cancelled = awaitStatus(created.id(), "CANCELLED");
        assertThat(cancelled.history()).extracting(OrderTransitionResponse::toStatus)
                .containsExactly("CREATED", "CANCELLING", "CANCELLED")
                .doesNotContain("AWAITING_PAYMENT");
    }

    @Test
    void cancellingDuringTheReservationEndsWithoutReleaseWhenStockIsMissing() {
        OrderResponse created = saga.create();
        MessageEnvelope reserve = saga.awaitCommand(created.id(), RESERVE);

        saga.cancel(created.id());
        saga.outOfStock(reserve);

        OrderResponse cancelled = awaitStatus(created.id(), "CANCELLED");
        assertThat(cancelled.cancellationReason()).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelled.history().getLast().trigger()).isEqualTo("INVENTORY_RESERVATION_FAILED");
        assertThat(saga.commands(created.id(), RELEASE, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void repeatingTheCancellationHasNoFurtherEffect() {
        OrderResponse created = saga.create();
        saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");

        assertThat(saga.cancel(created.id()).getStatusCode().value()).isEqualTo(202);
        assertThat(saga.cancel(created.id()).getStatusCode().value()).isEqualTo(202);

        saga.awaitCommand(created.id(), RELEASE);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4))
                .until(() -> saga.commands(created.id(), RELEASE, Duration.ofMillis(300)).size() == 1);
        assertThat(saga.get(created.id()).history()).hasSize(3);
    }

    @Test
    void cancellingAnAlreadyCancelledOrderReturnsItUnchanged() {
        OrderResponse created = saga.create();
        saga.outOfStock(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "CANCELLED");

        var response = saga.cancel(created.id());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().cancellationReason()).isEqualTo("OUT_OF_STOCK");
        assertThat(response.getBody().history()).hasSize(2);
    }

    @Test
    void orderWithoutSagaIsCancelledAtOnce() {
        OrderResponse created = saga.create();
        saga.awaitCommand(created.id(), RESERVE);
        jdbc.update("DELETE FROM order_saga WHERE order_id = ?", created.id());

        var response = saga.cancel(created.id());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().status()).isEqualTo("CANCELLED");
        assertThat(response.getBody().history()).extracting(OrderTransitionResponse::toStatus)
                .containsExactly("CREATED", "CANCELLING", "CANCELLED");
    }

    @Test
    void cancellingAnUnknownOrderIs404() {
        assertThatThrownBy(() -> saga.cancel(UUID.randomUUID()))
                .isInstanceOf(HttpClientErrorException.NotFound.class);
    }

    private OrderResponse awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).until(() -> saga.get(orderId).status().equals(status));
        return saga.get(orderId);
    }

    private String correlation(UUID orderId) {
        return jdbc.queryForObject("SELECT correlation_id FROM order_saga WHERE order_id = ?", String.class, orderId);
    }

    private String sagaStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM order_saga WHERE order_id = ?", String.class, orderId);
    }
}
