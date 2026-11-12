package com.mercatus.order.application.saga.usecase.support;

import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReservationReleaseRequester {

    private final InventoryCommandPublisher inventory;
    private final Duration releaseTimeout;

    public ReservationReleaseRequester(InventoryCommandPublisher inventory,
            @Value("${order.saga.release-timeout:PT5M}") Duration releaseTimeout) {
        this.inventory = inventory;
        this.releaseTimeout = releaseTimeout;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void request(OrderSaga saga, Instant now) {
        UUID commandId = inventory.requestRelease(saga.orderId(), saga.reservationId(), saga.correlationId());
        saga.releaseRequested(commandId, releaseTimeout, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestUntracked(OrderSaga saga, UUID reservationId) {
        inventory.requestRelease(saga.orderId(), reservationId, saga.correlationId());
    }
}
