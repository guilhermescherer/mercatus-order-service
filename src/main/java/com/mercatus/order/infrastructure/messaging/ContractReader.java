package com.mercatus.order.infrastructure.messaging;

import com.mercatus.order.infrastructure.messaging.payload.InventoryReservationFailedPayload;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservedPayload;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Lê o texto JSON que chegou do Kafka e o transforma nos records de contrato.
// Qualquer problema de formato vira ContractViolationException, que o
// DefaultErrorHandler (MessagingConfig) trata como "não adianta tentar de novo".
public final class ContractReader {

    private final JsonMapper jsonMapper;

    public ContractReader(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public MessageEnvelope readEnvelope(String json) {
        if (json == null || json.isBlank()) {
            throw new ContractViolationException("Message is empty");
        }

        try {
            return jsonMapper.readValue(json, MessageEnvelope.class);
        } catch (RuntimeException e) {
            throw violation("Message is not a valid envelope", e);
        }
    }

    public InventoryReservedPayload readReserved(JsonNode payload) {
        var value = convert(payload, InventoryReservedPayload.class);

        require(value.orderId(), "orderId");
        require(value.reservationId(), "reservationId");
        require(value.expiresAt(), "expiresAt");

        return value;
    }

    public InventoryReservationFailedPayload readReservationFailed(JsonNode payload) {
        var value = convert(payload, InventoryReservationFailedPayload.class);

        require(value.orderId(), "orderId");
        requireText(value.reason(), "reason");

        return value;
    }

    private <T> T convert(JsonNode payload, Class<T> type) {
        try {
            return jsonMapper.treeToValue(payload, type);
        } catch (RuntimeException e) {
            throw violation("Payload is incompatible with " + type.getSimpleName(), e);
        }
    }

    private ContractViolationException violation(String message, RuntimeException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ContractViolationException violation) {
                return violation;
            }
        }

        return new ContractViolationException(message, error);
    }

    private void require(Object value, String field) {
        if (value == null) {
            throw new ContractViolationException(field + " is required");
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ContractViolationException(field + " is required");
        }
    }
}
