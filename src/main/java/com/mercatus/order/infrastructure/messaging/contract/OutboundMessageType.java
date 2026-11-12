package com.mercatus.order.infrastructure.messaging.contract;

public enum OutboundMessageType {

    RESERVE_INVENTORY("inventory.ReserveInventory", 1),
    RELEASE_RESERVATION("inventory.ReleaseReservation", 1);

    private final String value;
    private final int schemaVersion;

    OutboundMessageType(String value, int schemaVersion) {
        this.value = value;
        this.schemaVersion = schemaVersion;
    }

    public String value() {
        return value;
    }

    public int schemaVersion() {
        return schemaVersion;
    }
}
