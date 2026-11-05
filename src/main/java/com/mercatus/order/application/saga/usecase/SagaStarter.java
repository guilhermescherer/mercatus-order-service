package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SagaStarter {

    private final OrderSagaRepository sagas;
    private final InventoryCommandPublisher inventory;
    private final Duration reserveTimeout;

    public SagaStarter(OrderSagaRepository sagas, InventoryCommandPublisher inventory,
            @Value("${order.saga.reserve-timeout:PT5M}") Duration reserveTimeout) {
        this.sagas = sagas;
        this.inventory = inventory;
        this.reserveTimeout = reserveTimeout;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OrderSaga start(Order order, String correlationId, Instant now) {
        UUID commandId = inventory.requestReservation(order, correlationId);
        var saga = OrderSaga.start(order.id(), correlationId, commandId, reserveTimeout, now);

        sagas.create(saga);

        return saga;
    }
}
