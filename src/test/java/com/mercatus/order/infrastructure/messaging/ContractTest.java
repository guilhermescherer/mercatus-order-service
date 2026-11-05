package com.mercatus.order.infrastructure.messaging;

import com.mercatus.order.infrastructure.messaging.payload.InventoryReservationFailedPayload;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservedPayload;
import com.mercatus.order.infrastructure.messaging.payload.ReserveInventoryPayload;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContractTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    private final ContractReader reader = new ContractReader(objectMapper);

    @Test
    void envelopeRoundTripPreservesFields() throws Exception {
        UUID orderId = UUID.randomUUID();
        var payload = new ReserveInventoryPayload(orderId, List.of(new ReserveInventoryPayload.Line("SKU-1", 2)));

        var envelope = new MessageEnvelope(
                UUID.randomUUID(), ReserveInventoryPayload.TYPE, 1, Instant.parse("2026-10-27T20:00:00Z"),
                orderId.toString(), "corr-1", "cause-1", objectMapper.valueToTree(payload));

        String json = objectMapper.writeValueAsString(envelope);
        MessageEnvelope back = objectMapper.readValue(json, MessageEnvelope.class);

        assertThat(back.type()).isEqualTo(ReserveInventoryPayload.TYPE);
        assertThat(back.schemaVersion()).isEqualTo(1);
        assertThat(back.correlationId()).isEqualTo("corr-1");

        var decoded = objectMapper.treeToValue(back.payload(), ReserveInventoryPayload.class);
        assertThat(decoded.orderId()).isEqualTo(orderId);
        assertThat(decoded.lines()).singleElement()
                .satisfies(line -> {
                    assertThat(line.skuCode()).isEqualTo("SKU-1");
                    assertThat(line.quantity()).isEqualTo(2);
                });
    }

    @Test
    void envelopeToleratesAdditiveFields() throws Exception {
        String json = """
                {
                  "messageId": "%s",
                  "type": "inventory.InventoryReserved",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1",
                  "correlationId": "corr-1",
                  "causationId": "cause-1",
                  "payload": { "orderId": "%s", "reservationId": "%s", "expiresAt": "2026-10-27T20:30:00Z" },
                  "newFutureField": "ignored"
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        MessageEnvelope envelope = objectMapper.readValue(json, MessageEnvelope.class);

        assertThat(envelope.type()).isEqualTo(InventoryReservedPayload.TYPE);
    }

    @Test
    void envelopeRejectsMissingRequiredField() {
        String json = """
                {
                  "messageId": "%s",
                  "type": "inventory.InventoryReserved",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1",
                  "payload": { "orderId": "%s" }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> objectMapper.readValue(json, MessageEnvelope.class))
                .hasRootCauseInstanceOf(ContractViolationException.class);
    }

    @Test
    void readReservedRequiresAllBusinessFields() {
        UUID orderId = UUID.randomUUID();
        var complete = new InventoryReservedPayload(orderId, UUID.randomUUID(), Instant.parse("2026-10-27T20:30:00Z"));

        assertThat(reader.readReserved(objectMapper.valueToTree(complete)).orderId()).isEqualTo(orderId);

        var missing = objectMapper.createObjectNode();
        missing.put("orderId", orderId.toString());

        assertThatThrownBy(() -> reader.readReserved(missing))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void readReservationFailedRequiresReason() {
        UUID orderId = UUID.randomUUID();
        var complete = new InventoryReservationFailedPayload(orderId, InventoryReservationFailedPayload.OUT_OF_STOCK);

        assertThat(reader.readReservationFailed(objectMapper.valueToTree(complete)).reason())
                .isEqualTo(InventoryReservationFailedPayload.OUT_OF_STOCK);

        var missing = objectMapper.createObjectNode();
        missing.put("orderId", orderId.toString());

        assertThatThrownBy(() -> reader.readReservationFailed(missing))
                .isInstanceOf(ContractViolationException.class);
    }

    @Test
    void readEnvelopeAcceptsCompleteMessage() {
        UUID messageId = UUID.randomUUID();
        String json = """
                {
                  "messageId": "%s",
                  "type": "inventory.Example",
                  "schemaVersion": 1,
                  "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1",
                  "correlationId": "corr-1",
                  "causationId": null,
                  "payload": { "orderId": "order-1" },
                  "extra": "ignored"
                }
                """.formatted(messageId);

        var envelope = reader.readEnvelope(json);

        assertThat(envelope.messageId()).isEqualTo(messageId);
        assertThat(envelope.payload().get("orderId").asString()).isEqualTo("order-1");
    }

    @Test
    void readEnvelopeReportsMissingFieldAsContractViolation() {
        String json = """
                { "type": "inventory.Example", "schemaVersion": 1, "occurredAt": "2026-10-27T20:00:00Z",
                  "aggregateId": "order-1", "correlationId": "corr-1", "payload": {} }
                """;

        assertThatThrownBy(() -> reader.readEnvelope(json))
                .isInstanceOf(ContractViolationException.class)
                .hasMessage("messageId is required");
    }

    @Test
    void readEnvelopeRejectsMalformedJson() {
        assertThatThrownBy(() -> reader.readEnvelope("not-json"))
                .isInstanceOf(ContractViolationException.class);
        assertThatThrownBy(() -> reader.readEnvelope(" "))
                .isInstanceOf(ContractViolationException.class);
    }
}
