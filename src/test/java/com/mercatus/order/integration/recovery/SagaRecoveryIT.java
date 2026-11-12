package com.mercatus.order.integration.recovery;

import com.mercatus.order.infrastructure.messaging.contract.ContractReader;
import com.mercatus.order.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.order.support.KafkaProbe;
import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.support.SagaHarness;
import com.mercatus.order.web.order.dto.OrderResponse;
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
                "order.saga.reserve-timeout=PT2S",
                "order.saga.release-timeout=PT2S",
                "order.saga.max-retries=2",
                "order.saga.recovery.interval-ms=500",
                "order.saga.recovery.initial-delay-ms=0"
        })
@DirtiesContext
class SagaRecoveryIT {

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
    void reservationWithoutReplyIsResentUntilTheSagaStalls() {
        OrderResponse created = saga.create();

        var commands = saga.awaitCommands(created.id(), RESERVE, 3);

        assertThat(commands).extracting(MessageEnvelope::messageId).doesNotHaveDuplicates();
        assertThat(commands).extracting(MessageEnvelope::correlationId).containsOnly(commands.getFirst().correlationId());
        await().atMost(Duration.ofSeconds(15)).until(() -> stalledAt(created.id()) != null);
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6))
                .until(() -> saga.commands(created.id(), RESERVE, Duration.ofMillis(300)).size() == 3);
        assertThat(retries(created.id())).isEqualTo(2);
        assertThat(saga.get(created.id()).status()).isEqualTo("CREATED");

        saga.reserved(commands.getLast());

        awaitStatus(created.id(), "AWAITING_PAYMENT");
        assertThat(stalledAt(created.id())).isNull();
        assertThat(retries(created.id())).isZero();
    }

    @Test
    void releaseWithoutReplyIsResentAndTheCancellationFinishesWhenItArrives() {
        OrderResponse created = saga.create();
        UUID reservationId = saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");
        saga.cancel(created.id());

        var releases = saga.awaitCommands(created.id(), RELEASE, 2);

        assertThat(releases).extracting(command -> command.payload().get("reservationId").asString())
                .containsOnly(reservationId.toString());
        saga.released(releases.getLast(), reservationId);

        awaitStatus(created.id(), "CANCELLED");
        assertThat(saga.get(created.id()).cancellationReason()).isEqualTo("CUSTOMER_REQUEST");
    }

    @Test
    void sagaThatGotItsReplyIsNotResent() {
        OrderResponse created = saga.create();
        saga.reserved(saga.awaitCommand(created.id(), RESERVE));
        awaitStatus(created.id(), "AWAITING_PAYMENT");

        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(6))
                .until(() -> saga.commands(created.id(), RESERVE, Duration.ofMillis(300)).size() == 1);
    }

    private OrderResponse awaitStatus(UUID orderId, String status) {
        await().atMost(Duration.ofSeconds(30)).until(() -> saga.get(orderId).status().equals(status));
        return saga.get(orderId);
    }

    private Object stalledAt(UUID orderId) {
        return jdbc.queryForObject("SELECT stalled_at FROM order_saga WHERE order_id = ?", Object.class, orderId);
    }

    private int retries(UUID orderId) {
        return jdbc.queryForObject("SELECT retries FROM order_saga WHERE order_id = ?", Integer.class, orderId);
    }
}
