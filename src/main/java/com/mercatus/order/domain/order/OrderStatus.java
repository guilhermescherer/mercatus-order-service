package com.mercatus.order.domain.order;

public enum OrderStatus {

    CREATED,
    AWAITING_PAYMENT,
    CANCELLING,
    CANCELLED;

    public boolean canMoveTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == AWAITING_PAYMENT || target == CANCELLING || target == CANCELLED;
            case AWAITING_PAYMENT -> target == CANCELLING;
            case CANCELLING -> target == CANCELLED;
            case CANCELLED -> false;
        };
    }

    public boolean carriesCancellationReason() {
        return this == CANCELLING || this == CANCELLED;
    }
}
