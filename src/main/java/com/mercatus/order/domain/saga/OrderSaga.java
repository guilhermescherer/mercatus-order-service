package com.mercatus.order.domain.saga;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class OrderSaga {

    private final UUID orderId;
    private SagaStep step;
    private SagaStatus status;
    private final String correlationId;
    private UUID reserveCommandId;
    private Instant reserveRequestedAt;
    private Instant reserveDeadline;
    private UUID reservationId;
    private Instant reservationExpiresAt;
    private UUID releaseCommandId;
    private Instant releaseRequestedAt;
    private Instant releaseDeadline;
    private int retries;
    private Instant stalledAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final long version;

    private OrderSaga(UUID orderId, SagaStep step, SagaStatus status, String correlationId, UUID reserveCommandId,
            Instant reserveRequestedAt, Instant reserveDeadline, UUID reservationId, Instant reservationExpiresAt,
            UUID releaseCommandId, Instant releaseRequestedAt, Instant releaseDeadline, int retries,
            Instant stalledAt, Instant createdAt, Instant updatedAt, long version) {
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
        if ((reservationId == null) != (reservationExpiresAt == null)) {
            throw new DomainRuleException("Reservation id and expiry go together");
        }
        if (status == SagaStatus.AWAITING_RESERVATION && reservationId != null) {
            throw new DomainRuleException("A saga awaiting reservation has no reservation yet");
        }
        if ((status == SagaStatus.RESERVED || status == SagaStatus.AWAITING_RELEASE) && reservationId == null) {
            throw new DomainRuleException("Saga in " + status + " requires a reservation");
        }
        if ((releaseCommandId == null) != (releaseRequestedAt == null)
                || (releaseCommandId == null) != (releaseDeadline == null)) {
            throw new DomainRuleException("Release command, request and deadline go together");
        }
        if (status == SagaStatus.AWAITING_RELEASE && releaseCommandId == null) {
            throw new DomainRuleException("A saga awaiting release requires a release command");
        }
        if (retries < 0) {
            throw new DomainRuleException("Retries must not be negative");
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
        this.releaseCommandId = releaseCommandId;
        this.releaseRequestedAt = releaseRequestedAt;
        this.releaseDeadline = releaseDeadline;
        this.retries = retries;
        this.stalledAt = stalledAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static OrderSaga start(UUID orderId, String correlationId, UUID reserveCommandId, Duration reserveTimeout,
            Instant now) {
        requirePositive(reserveTimeout);

        return new OrderSaga(orderId, SagaStep.RESERVE, SagaStatus.AWAITING_RESERVATION, correlationId,
                reserveCommandId, now, now.plus(reserveTimeout), null, null, null, null, null, 0, null, now, now, 0);
    }

    public static OrderSaga reconstitute(UUID orderId, SagaStep step, SagaStatus status, String correlationId,
            UUID reserveCommandId, Instant reserveRequestedAt, Instant reserveDeadline, UUID reservationId,
            Instant reservationExpiresAt, UUID releaseCommandId, Instant releaseRequestedAt, Instant releaseDeadline,
            int retries, Instant stalledAt, Instant createdAt, Instant updatedAt, long version) {
        return new OrderSaga(orderId, step, status, correlationId, reserveCommandId, reserveRequestedAt,
                reserveDeadline, reservationId, reservationExpiresAt, releaseCommandId, releaseRequestedAt,
                releaseDeadline, retries, stalledAt, createdAt, updatedAt, version);
    }

    public boolean awaitingReservation() {
        return status == SagaStatus.AWAITING_RESERVATION;
    }

    public boolean awaitingRelease() {
        return status == SagaStatus.AWAITING_RELEASE;
    }

    public boolean knowsReservation(UUID candidate) {
        return reservationId != null && reservationId.equals(candidate);
    }

    public void reservationAccepted(UUID reservationId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(reservationId);
        Objects.requireNonNull(expiresAt);
        requireStatus(SagaStatus.AWAITING_RESERVATION);

        this.status = SagaStatus.RESERVED;
        this.reservationId = reservationId;
        this.reservationExpiresAt = expiresAt;
        clearRetries();
        this.updatedAt = now;
    }

    public void reservationRejected(Instant now) {
        requireStatus(SagaStatus.AWAITING_RESERVATION);

        this.status = SagaStatus.CANCELLED;
        clearRetries();
        this.updatedAt = now;
    }

    public void releaseRequested(UUID commandId, Duration timeout, Instant now) {
        Objects.requireNonNull(commandId);
        requirePositive(timeout);
        requireStatus(SagaStatus.RESERVED);

        this.step = SagaStep.RELEASE;
        this.status = SagaStatus.AWAITING_RELEASE;
        this.releaseCommandId = commandId;
        this.releaseRequestedAt = now;
        this.releaseDeadline = now.plus(timeout);
        clearRetries();
        this.updatedAt = now;
    }

    public void compensated(Instant now) {
        if (status != SagaStatus.RESERVED && status != SagaStatus.AWAITING_RELEASE) {
            throw new DomainRuleException("Saga in " + status + " has no reservation to close");
        }

        this.status = SagaStatus.CANCELLED;
        clearRetries();
        this.updatedAt = now;
    }

    public Optional<Instant> currentDeadline() {
        return switch (status) {
            case AWAITING_RESERVATION -> Optional.of(reserveDeadline);
            case AWAITING_RELEASE -> Optional.of(releaseDeadline);
            case RESERVED, CANCELLED -> Optional.empty();
        };
    }

    public Optional<UUID> pendingCommandId() {
        return switch (status) {
            case AWAITING_RESERVATION -> Optional.of(reserveCommandId);
            case AWAITING_RELEASE -> Optional.of(releaseCommandId);
            case RESERVED, CANCELLED -> Optional.empty();
        };
    }

    public boolean overdue(Instant now) {
        return stalledAt == null && currentDeadline().map(deadline -> !now.isBefore(deadline)).orElse(false);
    }

    public void commandResent(UUID commandId, Duration timeout, Instant now) {
        Objects.requireNonNull(commandId);
        requirePositive(timeout);

        switch (status) {
            case AWAITING_RESERVATION -> {
                this.reserveCommandId = commandId;
                this.reserveRequestedAt = now;
                this.reserveDeadline = now.plus(timeout);
            }
            case AWAITING_RELEASE -> {
                this.releaseCommandId = commandId;
                this.releaseRequestedAt = now;
                this.releaseDeadline = now.plus(timeout);
            }
            case RESERVED, CANCELLED -> throw new DomainRuleException("Saga in " + status + " awaits no reply");
        }

        this.retries++;
        this.updatedAt = now;
    }

    public void deadlineExtended(Duration timeout, Instant now) {
        requirePositive(timeout);

        switch (status) {
            case AWAITING_RESERVATION -> this.reserveDeadline = now.plus(timeout);
            case AWAITING_RELEASE -> this.releaseDeadline = now.plus(timeout);
            case RESERVED, CANCELLED -> throw new DomainRuleException("Saga in " + status + " awaits no reply");
        }

        this.updatedAt = now;
    }

    public void stalled(Instant now) {
        if (currentDeadline().isEmpty()) {
            throw new DomainRuleException("Saga in " + status + " awaits no reply");
        }

        this.stalledAt = now;
        this.updatedAt = now;
    }

    private void clearRetries() {
        this.retries = 0;
        this.stalledAt = null;
    }

    private void requireStatus(SagaStatus expected) {
        if (status != expected) {
            throw new DomainRuleException("Saga is " + status + ", expected " + expected);
        }
    }

    private static void requirePositive(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new DomainRuleException("Saga timeout must be positive");
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

    public UUID releaseCommandId() {
        return releaseCommandId;
    }

    public Instant releaseRequestedAt() {
        return releaseRequestedAt;
    }

    public Instant releaseDeadline() {
        return releaseDeadline;
    }

    public int retries() {
        return retries;
    }

    public Instant stalledAt() {
        return stalledAt;
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
