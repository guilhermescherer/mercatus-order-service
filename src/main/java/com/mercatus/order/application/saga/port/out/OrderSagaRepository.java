package com.mercatus.order.application.saga.port.out;

import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderSagaRepository {

    void create(OrderSaga saga);

    Optional<OrderSaga> findByOrderId(UUID orderId);

    List<UUID> findOverdue(Instant now, int limit);

    void update(OrderSaga saga);
}
