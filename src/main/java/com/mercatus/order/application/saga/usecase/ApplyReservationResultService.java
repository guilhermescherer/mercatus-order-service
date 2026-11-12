package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.application.saga.usecase.support.ReservationReleaseRequester;
import com.mercatus.order.domain.order.CancellationReason;
import com.mercatus.order.domain.order.Order;
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
public class ApplyReservationResultService implements ApplyReservationResultUseCase {

    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    private final ReservationReleaseRequester releases;
    private final Clock clock;

    public ApplyReservationResultService(OrderRepository orders, OrderSagaRepository sagas,
            ReservationReleaseRequester releases, Clock clock) {
        this.orders = orders;
        this.sagas = sagas;
        this.releases = releases;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Outcome apply(ReservationResultCommand command) {
        var saga = sagas.findByOrderId(command.orderId());
        var order = orders.findById(command.orderId());

        if (saga.isEmpty() || order.isEmpty()) {
            return Outcome.IGNORED_UNKNOWN_ORDER;
        }

        if (!saga.get().awaitingReservation()) {
            return late(saga.get(), command);
        }

        Instant now = clock.instant().truncatedTo(MICROS);

        Outcome outcome = switch (order.get().status()) {
            case CREATED -> decide(order.get(), saga.get(), command, now);
            case CANCELLING -> compensate(order.get(), saga.get(), command, now);
            case AWAITING_PAYMENT, CANCELLED -> Outcome.IGNORED_ALREADY_DECIDED;
        };

        if (outcome != Outcome.IGNORED_ALREADY_DECIDED) {
            sagas.update(saga.get());
        }

        return outcome;
    }

    private Outcome decide(Order order, OrderSaga saga, ReservationResultCommand command, Instant now) {
        switch (command.result()) {
            case RESERVED -> {
                orders.update(order.awaitPayment(now));
                saga.reservationAccepted(command.reservationId(), command.expiresAt(), now);
            }
            case OUT_OF_STOCK -> {
                orders.update(order.cancel(CancellationReason.OUT_OF_STOCK,
                        TransitionTrigger.INVENTORY_RESERVATION_FAILED, now));
                saga.reservationRejected(now);
            }
        }

        return Outcome.APPLIED;
    }

    private Outcome compensate(Order order, OrderSaga saga, ReservationResultCommand command, Instant now) {
        return switch (command.result()) {
            case RESERVED -> {
                saga.reservationAccepted(command.reservationId(), command.expiresAt(), now);
                releases.request(saga, now);
                yield Outcome.RELEASE_REQUESTED;
            }
            case OUT_OF_STOCK -> {
                saga.reservationRejected(now);
                orders.update(order.completeCancellation(TransitionTrigger.INVENTORY_RESERVATION_FAILED,
                        TransitionActor.SAGA, now));
                yield Outcome.APPLIED;
            }
        };
    }

    private Outcome late(OrderSaga saga, ReservationResultCommand command) {
        if (command.result() == Result.RESERVED && saga.status() == SagaStatus.CANCELLED
                && !saga.knowsReservation(command.reservationId())) {
            releases.requestUntracked(saga, command.reservationId());
            return Outcome.RELEASE_REQUESTED;
        }

        return Outcome.IGNORED_ALREADY_DECIDED;
    }
}
