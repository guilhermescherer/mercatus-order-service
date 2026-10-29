package com.mercatus.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservationFailedPayload;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservedPayload;

public final class ContractReader {

    private final ObjectMapper objectMapper;

    public ContractReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
            return objectMapper.treeToValue(payload, type);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ContractViolationException("Payload is incompatible with " + type.getSimpleName(), e);
        }
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
