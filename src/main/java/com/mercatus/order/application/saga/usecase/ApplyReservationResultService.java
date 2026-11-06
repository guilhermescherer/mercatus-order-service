package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.domain.order.CancellationReason;
import com.mercatus.order.domain.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.time.temporal.ChronoUnit.MICROS;

@Service
public class ApplyReservationResultService implements ApplyReservationResultUseCase {

    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    private final Clock clock;

    public ApplyReservationResultService(OrderRepository orders, OrderSagaRepository sagas, Clock clock) {
        this.orders = orders;
        this.sagas = sagas;
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

        if (!saga.get().awaitingReservation() || order.get().status() != OrderStatus.CREATED) {
            return Outcome.IGNORED_ALREADY_DECIDED;
        }

        Instant now = clock.instant().truncatedTo(MICROS);

        switch (command.result()) {
            case RESERVED -> {
                orders.update(order.get().awaitPayment(now));
                saga.get().reservationAccepted(command.reservationId(), command.expiresAt(), now);
            }
            case OUT_OF_STOCK -> {
                orders.update(order.get().cancel(CancellationReason.OUT_OF_STOCK, "INVENTORY_RESERVATION_FAILED", now));
                saga.get().reservationRejected(now);
            }
        }

        sagas.update(saga.get());

        return Outcome.APPLIED;
    }
}
