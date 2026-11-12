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

    private OrderSaga reserved() {
        var saga = started();
        saga.reservationAccepted(UUID.randomUUID(), NOW.plus(Duration.ofMinutes(30)), NOW.plusSeconds(1));
        return saga;
    }

    @Test
    void releaseRequestMovesToTheReleaseStepWithItsOwnDeadline() {
        var saga = reserved();
        UUID commandId = UUID.randomUUID();

        saga.releaseRequested(commandId, Duration.ofMinutes(2), NOW.plusSeconds(10));

        assertThat(saga.step()).isEqualTo(SagaStep.RELEASE);
        assertThat(saga.status()).isEqualTo(SagaStatus.AWAITING_RELEASE);
        assertThat(saga.pendingCommandId()).contains(commandId);
        assertThat(saga.currentDeadline()).contains(NOW.plusSeconds(10).plus(Duration.ofMinutes(2)));
        assertThat(saga.knowsReservation(saga.reservationId())).isTrue();
        assertThat(saga.knowsReservation(UUID.randomUUID())).isFalse();
    }

    @Test
    void compensationClosesReservedOrReleasingSagasOnly() {
        var releasing = reserved();
        releasing.releaseRequested(UUID.randomUUID(), Duration.ofMinutes(2), NOW.plusSeconds(10));
        releasing.compensated(NOW.plusSeconds(20));
        var expired = reserved();
        expired.compensated(NOW.plusSeconds(20));

        assertThat(releasing.status()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(expired.status()).isEqualTo(SagaStatus.CANCELLED);
        assertThat(releasing.currentDeadline()).isEmpty();
        assertThatThrownBy(() -> started().compensated(NOW)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> releasing.compensated(NOW)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> started().releaseRequested(UUID.randomUUID(), Duration.ofMinutes(1), NOW))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void overdueSagaCanBeResentExtendedOrStalled() {
        var saga = started();
        Instant late = saga.reserveDeadline();
        UUID resent = UUID.randomUUID();

        assertThat(saga.overdue(late.minusSeconds(1))).isFalse();
        assertThat(saga.overdue(late)).isTrue();

        saga.commandResent(resent, Duration.ofMinutes(5), late);
        assertThat(saga.retries()).isEqualTo(1);
        assertThat(saga.reserveCommandId()).isEqualTo(resent);
        assertThat(saga.overdue(late)).isFalse();

        saga.deadlineExtended(Duration.ofMinutes(1), late.plus(Duration.ofMinutes(5)));
        assertThat(saga.retries()).isEqualTo(1);
        assertThat(saga.reserveDeadline()).isEqualTo(late.plus(Duration.ofMinutes(6)));

        saga.stalled(late.plus(Duration.ofMinutes(7)));
        assertThat(saga.stalledAt()).isNotNull();
        assertThat(saga.overdue(late.plus(Duration.ofHours(1)))).isFalse();
    }

    @Test
    void answerAfterStallingClearsRetriesAndStall() {
        var saga = started();
        saga.commandResent(UUID.randomUUID(), Duration.ofMinutes(5), saga.reserveDeadline());
        saga.stalled(NOW.plus(Duration.ofHours(1)));

        saga.reservationAccepted(UUID.randomUUID(), NOW.plus(Duration.ofHours(2)), NOW.plus(Duration.ofHours(1)));

        assertThat(saga.retries()).isZero();
        assertThat(saga.stalledAt()).isNull();
    }

    @Test
    void sagaWithoutPendingReplyCannotBeRetried() {
        var saga = reserved();

        assertThat(saga.overdue(NOW.plus(Duration.ofDays(1)))).isFalse();
        assertThatThrownBy(() -> saga.commandResent(UUID.randomUUID(), Duration.ofMinutes(1), NOW))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> saga.stalled(NOW)).isInstanceOf(DomainRuleException.class);
    }
}
