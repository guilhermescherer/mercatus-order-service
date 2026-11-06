package com.mercatus.order.application.saga.port.out;

import com.mercatus.order.domain.saga.OrderSaga;
import java.util.Optional;
import java.util.UUID;

public interface OrderSagaRepository {

    void create(OrderSaga saga);

    Optional<OrderSaga> findByOrderId(UUID orderId);

    void update(OrderSaga saga);
}
