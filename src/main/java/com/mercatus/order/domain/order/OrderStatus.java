package com.mercatus.order.domain.order;

public enum OrderStatus {

    CREATED,
    AWAITING_PAYMENT,
    CANCELLED;

    public boolean canMoveTo(OrderStatus target) {
        return switch (this) {
            case CREATED -> target == AWAITING_PAYMENT || target == CANCELLED;
            case AWAITING_PAYMENT, CANCELLED -> false;
        };
    }
}
