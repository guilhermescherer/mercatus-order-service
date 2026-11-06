package com.mercatus.order.domain.saga;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSagaTest {

    private static final Instant NOW = Instant.parse("2026-11-04T20:00:00Z");

    private OrderSaga started() {
        return OrderSaga.start(UUID.randomUUID(), "corr-1", UUID.randomUUID(), Duration.ofMinutes(5), NOW);
    }

    @Test
    void startsAwaitingReservationWithDeadline() {
        var saga = started();

        assertThat(saga.step()).isEqualTo(SagaStep.RESERVE);
        assertThat(saga.status()).isEqualTo(SagaStatus.AWAITING_RESERVATION);
        assertThat(saga.reserveRequestedAt()).isEqualTo(NOW);
        assertThat(saga.reserveDeadline()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(saga.reservationId()).isNull();
    }

    @Test
    void acceptedReservationIsRecorded() {
        var saga = started();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = NOW.plus(Duration.ofMinutes(30));

        saga.reservationAccepted(reservationId, expiresAt, NOW.plusSeconds(1));

        assertThat(saga.status()).isEqualTo(SagaStatus.RESERVED);
        assertThat(saga.reservationId()).isEqualTo(reservationId);
        assertThat(saga.reservationExpiresAt()).isEqualTo(expiresAt);
        assertThat(saga.awaitingReservation()).isFalse();
    }

    @Test
    void rejectedReservationCancelsTheSaga() {
        var saga = started();

        saga.reservationRejected(NOW.plusSeconds(1));

        assertThat(saga.status()).isEqualTo(SagaStatus.CANCELLED);
    }

    @Test
    void decidedSagaRejectsAnotherResult() {
        var saga = started();
        saga.reservationRejected(NOW.plusSeconds(1));

        assertThatThrownBy(() -> saga.reservationAccepted(UUID.randomUUID(), NOW, NOW))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> saga.reservationRejected(NOW)).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void requiresCorrelationAndPositiveTimeout() {
        assertThatThrownBy(() -> OrderSaga.start(UUID.randomUUID(), " ", UUID.randomUUID(), Duration.ofMinutes(5), NOW))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> OrderSaga.start(UUID.randomUUID(), "c", UUID.randomUUID(), Duration.ZERO, NOW))
                .isInstanceOf(DomainRuleException.class);
    }
}
