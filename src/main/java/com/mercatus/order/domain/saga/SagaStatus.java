package com.mercatus.order.domain.saga;

public enum SagaStatus {

    AWAITING_RESERVATION,
    RESERVED,
    AWAITING_RELEASE,
    CANCELLED
}
