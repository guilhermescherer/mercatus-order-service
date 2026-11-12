package com.mercatus.order.infrastructure.messaging.contract.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record InventoryReservationFailedPayload(UUID orderId, Reason reason) {

    public static final String TYPE = "inventory.InventoryReservationFailed";

    public enum Reason {
        OUT_OF_STOCK
    }
}
