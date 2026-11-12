package com.mercatus.order.infrastructure.persistence.saga;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderSagaJpaRepository extends JpaRepository<OrderSagaEntity, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO order_saga (order_id, step, status, correlation_id, reserve_command_id,
                reserve_requested_at, reserve_deadline, reservation_id, reservation_expires_at,
                created_at, updated_at, version)
            VALUES (:orderId, :step, :status, :correlationId, :commandId, :requestedAt, :deadline,
                NULL, NULL, :createdAt, :updatedAt, 0)
            """, nativeQuery = true)
    int insert(
            @Param("orderId") UUID orderId,
            @Param("step") String step,
            @Param("status") String status,
            @Param("correlationId") String correlationId,
            @Param("commandId") UUID commandId,
            @Param("requestedAt") Instant requestedAt,
            @Param("deadline") Instant deadline,
            @Param("createdAt") Instant createdAt,
            @Param("updatedAt") Instant updatedAt);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE order_saga
            SET step = :step, status = :status, reserve_command_id = :reserveCommandId,
                reserve_requested_at = :reserveRequestedAt, reserve_deadline = :reserveDeadline,
                reservation_id = :reservationId, reservation_expires_at = :expiresAt,
                release_command_id = :releaseCommandId, release_requested_at = :releaseRequestedAt,
                release_deadline = :releaseDeadline, retries = :retries, stalled_at = :stalledAt,
                updated_at = :updatedAt, version = version + 1
            WHERE order_id = :orderId AND version = :version
            """, nativeQuery = true)
    int update(
            @Param("orderId") UUID orderId,
            @Param("step") String step,
            @Param("status") String status,
            @Param("reserveCommandId") UUID reserveCommandId,
            @Param("reserveRequestedAt") Instant reserveRequestedAt,
            @Param("reserveDeadline") Instant reserveDeadline,
            @Param("reservationId") UUID reservationId,
            @Param("expiresAt") Instant expiresAt,
            @Param("releaseCommandId") UUID releaseCommandId,
            @Param("releaseRequestedAt") Instant releaseRequestedAt,
            @Param("releaseDeadline") Instant releaseDeadline,
            @Param("retries") int retries,
            @Param("stalledAt") Instant stalledAt,
            @Param("updatedAt") Instant updatedAt,
            @Param("version") long version);

    @Query(value = """
            SELECT order_id FROM order_saga
            WHERE stalled_at IS NULL
              AND ((status = 'AWAITING_RESERVATION' AND reserve_deadline <= :now)
                OR (status = 'AWAITING_RELEASE' AND release_deadline <= :now))
            ORDER BY updated_at, order_id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findOverdue(@Param("now") Instant now, @Param("limit") int limit);
}
