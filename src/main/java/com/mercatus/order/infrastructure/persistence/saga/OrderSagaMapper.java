package com.mercatus.order.infrastructure.persistence.saga;

import com.mercatus.order.domain.saga.OrderSaga;
import com.mercatus.order.domain.saga.SagaStatus;
import com.mercatus.order.domain.saga.SagaStep;

final class OrderSagaMapper {

    private OrderSagaMapper() {
    }

    static OrderSaga toDomain(OrderSagaEntity entity) {
        return OrderSaga.reconstitute(entity.orderId, SagaStep.valueOf(entity.step), SagaStatus.valueOf(entity.status),
                entity.correlationId, entity.reserveCommandId, entity.reserveRequestedAt, entity.reserveDeadline,
                entity.reservationId, entity.reservationExpiresAt, entity.releaseCommandId, entity.releaseRequestedAt,
                entity.releaseDeadline, entity.retries, entity.stalledAt, entity.createdAt, entity.updatedAt,
                entity.version);
    }
}
