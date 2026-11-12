package com.mercatus.order.application.order.usecase;

import com.mercatus.order.application.exception.ResourceNotFoundException;
import com.mercatus.order.application.order.port.in.CancelOrderUseCase;
import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.application.saga.usecase.support.ReservationReleaseRequester;
import com.mercatus.order.domain.order.CancellationReason;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.OrderStatus;
import com.mercatus.order.domain.order.TransitionActor;
import com.mercatus.order.domain.order.TransitionTrigger;
import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static java.time.temporal.ChronoUnit.MICROS;

@Service
public class CancelOrderService implements CancelOrderUseCase {

    private final OrderRepository orders;
    private final OrderSagaRepository sagas;
    private final ReservationReleaseRequester releases;
    private final Clock clock;

    public CancelOrderService(OrderRepository orders, OrderSagaRepository sagas,
            ReservationReleaseRequester releases, Clock clock) {
        this.orders = orders;
        this.sagas = sagas;
        this.releases = releases;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Result cancel(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.cancellationStarted()) {
            return result(order);
        }

        Instant now = clock.instant().truncatedTo(MICROS);
        Order next = order.requestCancellation(CancellationReason.CUSTOMER_REQUEST,
                TransitionTrigger.CUSTOMER_CANCELLATION_REQUESTED, TransitionActor.CUSTOMER, now);
        OrderSaga saga = sagas.findByOrderId(orderId).orElse(null);

        if (saga == null) {
            next = complete(next, now);
        } else {
            switch (saga.status()) {
                case RESERVED -> {
                    releases.request(saga, now);
                    sagas.update(saga);
                }
                case CANCELLED -> next = complete(next, now);
                case AWAITING_RESERVATION, AWAITING_RELEASE -> {
                }
            }
        }

        orders.update(next);

        return result(next);
    }

    private Order complete(Order cancelling, Instant now) {
        return cancelling.completeCancellation(TransitionTrigger.CUSTOMER_CANCELLATION_REQUESTED,
                TransitionActor.CUSTOMER, now);
    }

    private Result result(Order order) {
        return new Result(order, order.status() == OrderStatus.CANCELLED);
    }
}
