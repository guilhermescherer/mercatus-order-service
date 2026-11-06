package com.mercatus.order.domain.saga;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class OrderSaga {

    private final UUID orderId;
    private final SagaStep step;
    private SagaStatus status;
    private final String correlationId;
    private final UUID reserveCommandId;
    private final Instant reserveRequestedAt;
    private final Instant reserveDeadline;
    private UUID reservationId;
    private Instant reservationExpiresAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private OrderSaga(UUID orderId, SagaStep step, SagaStatus status, String correlationId, UUID reserveCommandId,
            Instant reserveRequestedAt, Instant reserveDeadline, UUID reservationId, Instant reservationExpiresAt,
            Instant createdAt, Instant updatedAt, long version) {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(step);
        Objects.requireNonNull(status);
        Objects.requireNonNull(reserveCommandId);
        Objects.requireNonNull(reserveRequestedAt);
        Objects.requireNonNull(reserveDeadline);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);

        if (correlationId == null || correlationId.isBlank()) {
            throw new DomainRuleException("Saga requires a correlation id");
        }
        if (!reserveDeadline.isAfter(reserveRequestedAt)) {
            throw new DomainRuleException("Reserve deadline must be after the request");
        }
        if ((status == SagaStatus.RESERVED) != (reservationId != null && reservationExpiresAt != null)) {
            throw new DomainRuleException("Only reserved sagas carry a reservation");
        }

        this.orderId = orderId;
        this.step = step;
        this.status = status;
        this.correlationId = correlationId;
        this.reserveCommandId = reserveCommandId;
        this.reserveRequestedAt = reserveRequestedAt;
        this.reserveDeadline = reserveDeadline;
        this.reservationId = reservationId;
        this.reservationExpiresAt = reservationExpiresAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static OrderSaga start(UUID orderId, String correlationId, UUID reserveCommandId, Duration reserveTimeout,
            Instant now) {
        if (reserveTimeout == null || reserveTimeout.isNegative() || reserveTimeout.isZero()) {
            throw new DomainRuleException("Reserve timeout must be positive");
        }

        return new OrderSaga(orderId, SagaStep.RESERVE, SagaStatus.AWAITING_RESERVATION, correlationId,
                reserveCommandId, now, now.plus(reserveTimeout), null, null, now, now, 0);
    }

    public static OrderSaga reconstitute(UUID orderId, SagaStep step, SagaStatus status, String correlationId,
            UUID reserveCommandId, Instant reserveRequestedAt, Instant reserveDeadline, UUID reservationId,
            Instant reservationExpiresAt, Instant createdAt, Instant updatedAt, long version) {
        return new OrderSaga(orderId, step, status, correlationId, reserveCommandId, reserveRequestedAt,
                reserveDeadline, reservationId, reservationExpiresAt, createdAt, updatedAt, version);
    }

    public boolean awaitingReservation() {
        return status == SagaStatus.AWAITING_RESERVATION;
    }

    public void reservationAccepted(UUID reservationId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(reservationId);
        Objects.requireNonNull(expiresAt);
        requireAwaitingReservation();

        this.status = SagaStatus.RESERVED;
        this.reservationId = reservationId;
        this.reservationExpiresAt = expiresAt;
        this.updatedAt = now;
    }

    public void reservationRejected(Instant now) {
        requireAwaitingReservation();

        this.status = SagaStatus.CANCELLED;
        this.updatedAt = now;
    }

    private void requireAwaitingReservation() {
        if (!awaitingReservation()) {
            throw new DomainRuleException("Saga is not awaiting a reservation");
        }
    }

    public UUID orderId() {
        return orderId;
    }

    public SagaStep step() {
        return step;
    }

    public SagaStatus status() {
        return status;
    }

    public String correlationId() {
        return correlationId;
    }

    public UUID reserveCommandId() {
        return reserveCommandId;
    }

    public Instant reserveRequestedAt() {
        return reserveRequestedAt;
    }

    public Instant reserveDeadline() {
        return reserveDeadline;
    }

    public UUID reservationId() {
        return reservationId;
    }

    public Instant reservationExpiresAt() {
        return reservationExpiresAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
