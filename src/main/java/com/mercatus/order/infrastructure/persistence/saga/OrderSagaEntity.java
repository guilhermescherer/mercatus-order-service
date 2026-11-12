package com.mercatus.order.infrastructure.persistence.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_saga")
class OrderSagaEntity {

    @Id
    @Column(name = "order_id")
    UUID orderId;

    String step;

    String status;

    @Column(name = "correlation_id")
    String correlationId;

    @Column(name = "reserve_command_id")
    UUID reserveCommandId;

    @Column(name = "reserve_requested_at")
    Instant reserveRequestedAt;

    @Column(name = "reserve_deadline")
    Instant reserveDeadline;

    @Column(name = "reservation_id")
    UUID reservationId;

    @Column(name = "reservation_expires_at")
    Instant reservationExpiresAt;

    @Column(name = "release_command_id")
    UUID releaseCommandId;

    @Column(name = "release_requested_at")
    Instant releaseRequestedAt;

    @Column(name = "release_deadline")
    Instant releaseDeadline;

    int retries;

    @Column(name = "stalled_at")
    Instant stalledAt;

    @Column(name = "created_at")
    Instant createdAt;

    @Column(name = "updated_at")
    Instant updatedAt;

    @Version
    long version;

    protected OrderSagaEntity() {
    }
}
