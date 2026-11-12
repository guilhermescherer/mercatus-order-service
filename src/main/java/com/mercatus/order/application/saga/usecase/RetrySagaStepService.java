package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.in.RetrySagaStepUseCase;
import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static java.time.temporal.ChronoUnit.MICROS;

@Service
public class RetrySagaStepService implements RetrySagaStepUseCase {

    private final OrderSagaRepository sagas;
    private final OrderRepository orders;
    private final InventoryCommandPublisher inventory;
    private final Clock clock;
    private final Duration reserveTimeout;
    private final Duration releaseTimeout;
    private final int maxRetries;

    public RetrySagaStepService(OrderSagaRepository sagas, OrderRepository orders, InventoryCommandPublisher inventory,
            Clock clock,
            @Value("${order.saga.reserve-timeout:PT5M}") Duration reserveTimeout,
            @Value("${order.saga.release-timeout:PT5M}") Duration releaseTimeout,
            @Value("${order.saga.max-retries:5}") int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries must not be negative");
        }
        this.sagas = sagas;
        this.orders = orders;
        this.inventory = inventory;
        this.clock = clock;
        this.reserveTimeout = reserveTimeout;
        this.releaseTimeout = releaseTimeout;
        this.maxRetries = maxRetries;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome retry(UUID orderId) {
        Instant now = clock.instant().truncatedTo(MICROS);
        OrderSaga saga = sagas.findByOrderId(orderId).orElse(null);

        if (saga == null || !saga.overdue(now)) {
            return Outcome.NOT_OVERDUE;
        }

        Duration timeout = saga.awaitingReservation() ? reserveTimeout : releaseTimeout;
        Outcome outcome;

        if (inventory.isPending(saga.pendingCommandId().orElseThrow())) {
            saga.deadlineExtended(timeout, now);
            outcome = Outcome.DEADLINE_EXTENDED;
        } else if (saga.retries() >= maxRetries) {
            saga.stalled(now);
            outcome = Outcome.STALLED;
        } else {
            saga.commandResent(resend(saga), timeout, now);
            outcome = Outcome.RESENT;
        }

        sagas.update(saga);

        return outcome;
    }

    private UUID resend(OrderSaga saga) {
        if (saga.awaitingReservation()) {
            var order = orders.findById(saga.orderId()).orElseThrow();
            return inventory.requestReservation(order, saga.correlationId());
        }

        return inventory.requestRelease(saga.orderId(), saga.reservationId(), saga.correlationId());
    }
}
