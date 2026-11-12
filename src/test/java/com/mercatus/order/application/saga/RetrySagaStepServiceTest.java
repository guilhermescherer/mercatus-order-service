package com.mercatus.order.application.saga;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.saga.port.in.RetrySagaStepUseCase.Outcome;
import com.mercatus.order.application.saga.port.out.InventoryCommandPublisher;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import com.mercatus.order.application.saga.usecase.RetrySagaStepService;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.saga.OrderSaga;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrySagaStepServiceTest {

    private static final Instant START = Instant.parse("2026-11-06T20:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Instant LATE = START.plus(TIMEOUT).plusSeconds(1);

    private final OrderSagaRepository sagas = mock(OrderSagaRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final InventoryCommandPublisher inventory = mock(InventoryCommandPublisher.class);
    private final RetrySagaStepService service = new RetrySagaStepService(sagas, orders, inventory,
            Clock.fixed(LATE, ZoneOffset.UTC), TIMEOUT, TIMEOUT, 2);

    @Test
    void overdueReservationIsResentWithTheOriginalCorrelation() {
        Order order = order();
        OrderSaga saga = OrderSaga.start(order.id(), "corr-1", UUID.randomUUID(), TIMEOUT, START);
        UUID resent = UUID.randomUUID();
        when(sagas.findByOrderId(order.id())).thenReturn(Optional.of(saga));
        when(orders.findById(order.id())).thenReturn(Optional.of(order));
        when(inventory.requestReservation(order, "corr-1")).thenReturn(resent);

        assertThat(service.retry(order.id())).isEqualTo(Outcome.RESENT);

        assertThat(saga.reserveCommandId()).isEqualTo(resent);
        assertThat(saga.retries()).isEqualTo(1);
        assertThat(saga.reserveDeadline()).isEqualTo(LATE.plus(TIMEOUT));
        verify(sagas).update(saga);
    }

    @Test
    void overdueReleaseIsResentForTheKnownReservation() {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        OrderSaga saga = OrderSaga.start(orderId, "corr-1", UUID.randomUUID(), TIMEOUT, START);
        saga.reservationAccepted(reservationId, START.plus(Duration.ofHours(1)), START);
        saga.releaseRequested(UUID.randomUUID(), TIMEOUT, START);
        when(sagas.findByOrderId(orderId)).thenReturn(Optional.of(saga));
        when(inventory.requestRelease(orderId, reservationId, "corr-1")).thenReturn(UUID.randomUUID());

        assertThat(service.retry(orderId)).isEqualTo(Outcome.RESENT);

        verify(inventory).requestRelease(orderId, reservationId, "corr-1");
        assertThat(saga.releaseDeadline()).isEqualTo(LATE.plus(TIMEOUT));
    }

    @Test
    void commandStillWaitingInTheOutboxOnlyExtendsTheDeadline() {
        UUID orderId = UUID.randomUUID();
        UUID commandId = UUID.randomUUID();
        OrderSaga saga = OrderSaga.start(orderId, "corr-1", commandId, TIMEOUT, START);
        when(sagas.findByOrderId(orderId)).thenReturn(Optional.of(saga));
        when(inventory.isPending(commandId)).thenReturn(true);

        assertThat(service.retry(orderId)).isEqualTo(Outcome.DEADLINE_EXTENDED);

        assertThat(saga.retries()).isZero();
        assertThat(saga.reserveCommandId()).isEqualTo(commandId);
        verify(inventory, never()).requestReservation(any(), any());
    }

    @Test
    void exhaustedRetriesStallTheSaga() {
        UUID orderId = UUID.randomUUID();
        OrderSaga saga = OrderSaga.start(orderId, "corr-1", UUID.randomUUID(), TIMEOUT, START);
        saga.commandResent(UUID.randomUUID(), Duration.ofSeconds(1), START);
        saga.commandResent(UUID.randomUUID(), Duration.ofSeconds(1), START);
        when(sagas.findByOrderId(orderId)).thenReturn(Optional.of(saga));

        assertThat(service.retry(orderId)).isEqualTo(Outcome.STALLED);

        assertThat(saga.stalledAt()).isEqualTo(LATE);
        verify(inventory, never()).requestReservation(any(), any());
    }

    @Test
    void sagaThatIsNoLongerOverdueIsLeftAlone() {
        UUID orderId = UUID.randomUUID();
        OrderSaga saga = OrderSaga.start(orderId, "corr-1", UUID.randomUUID(), Duration.ofHours(1), START);
        when(sagas.findByOrderId(orderId)).thenReturn(Optional.of(saga));

        assertThat(service.retry(orderId)).isEqualTo(Outcome.NOT_OVERDUE);
        assertThat(service.retry(UUID.randomUUID())).isEqualTo(Outcome.NOT_OVERDUE);

        verify(sagas, never()).update(any());
    }
}
