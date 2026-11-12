package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.in.ApplyReservationClosureUseCase;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.domain.order.CancellationReason;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.OrderStatus;
import com.mercatus.order.domain.order.TransitionActor;
import com.mercatus.order.domain.order.TransitionTrigger;
import com.mercatus.order.domain.saga.OrderSaga;
import com.mercatus.order.domain.saga.SagaStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.time.temporal.ChronoUnit.MICROS;

@Service
public class ApplyReservationClosureService implements ApplyReservationClosureUseCase {

    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    private final Clock clock;

    public ApplyReservationClosureService(OrderRepository orders, OrderSagaRepository sagas, Clock clock) {
        this.orders = orders;
        this.sagas = sagas;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Outcome apply(ReservationClosureCommand command) {
        var saga = sagas.findByOrderId(command.orderId());
        var order = orders.findById(command.orderId());

        if (saga.isEmpty() || order.isEmpty()) {
            return Outcome.IGNORED_UNKNOWN_ORDER;
        }

        if (!saga.get().knowsReservation(command.reservationId())) {
            return Outcome.IGNORED_UNKNOWN_RESERVATION;
        }

        OrderSaga current = saga.get();
        Order existing = order.get();

        if (current.status() == SagaStatus.CANCELLED) {
            return Outcome.IGNORED_ALREADY_DECIDED;
        }

        Instant now = clock.instant().truncatedTo(MICROS);
        Order next;

        if (current.awaitingRelease() && existing.status() == OrderStatus.CANCELLING) {
            next = existing.completeCancellation(trigger(command.closure()), TransitionActor.SAGA, now);
        } else if (current.status() == SagaStatus.RESERVED && command.closure() == Closure.EXPIRED
                && existing.status() == OrderStatus.AWAITING_PAYMENT) {
            next = expire(existing, now);
        } else {
            return Outcome.IGNORED_UNEXPECTED;
        }

        current.compensated(now);
        sagas.update(current);
        orders.update(next);

        return Outcome.APPLIED;
    }

    private TransitionTrigger trigger(Closure closure) {
        return switch (closure) {
            case RELEASED -> TransitionTrigger.RESERVATION_RELEASED;
            case EXPIRED -> TransitionTrigger.RESERVATION_EXPIRED;
        };
    }

    private Order expire(Order order, Instant now) {
        return order.requestCancellation(CancellationReason.RESERVATION_EXPIRED,
                        TransitionTrigger.RESERVATION_EXPIRED, TransitionActor.SAGA, now)
                .completeCancellation(TransitionTrigger.RESERVATION_EXPIRED, TransitionActor.SAGA, now);
    }
}
