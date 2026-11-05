package com.mercatus.order.infrastructure.persistence.saga;

import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.domain.saga.OrderSaga;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class OrderSagaRepositoryAdapter implements OrderSagaRepository {

    private final OrderSagaJpaRepository repository;

    OrderSagaRepositoryAdapter(OrderSagaJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void create(OrderSaga saga) {
        repository.insert(saga.orderId(), saga.step().name(), saga.status().name(), saga.correlationId(),
                saga.reserveCommandId(), saga.reserveRequestedAt(), saga.reserveDeadline(), saga.createdAt(),
                saga.updatedAt());
    }

    @Override
    public Optional<OrderSaga> findByOrderId(UUID orderId) {
        return repository.findById(orderId).map(OrderSagaMapper::toDomain);
    }

    @Override
    public void update(OrderSaga saga) {
        int updated = repository.update(saga.orderId(), saga.step().name(), saga.status().name(),
                saga.reservationId(), saga.reservationExpiresAt(), saga.updatedAt(), saga.version());

        if (updated == 0) {
            throw new OptimisticLockingFailureException("Saga " + saga.orderId() + " was changed concurrently");
        }
    }
}
