package com.mercatus.order.infrastructure.messaging.contract.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseReservationPayload(UUID orderId, UUID reservationId) {
}
