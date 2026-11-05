package com.mercatus.order.infrastructure.persistence.saga;

import java.time.Instant;
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
            SET step = :step, status = :status, reservation_id = :reservationId,
                reservation_expires_at = :expiresAt, updated_at = :updatedAt, version = version + 1
            WHERE order_id = :orderId AND version = :version
            """, nativeQuery = true)
    int update(
            @Param("orderId") UUID orderId,
            @Param("step") String step,
            @Param("status") String status,
            @Param("reservationId") UUID reservationId,
            @Param("expiresAt") Instant expiresAt,
            @Param("updatedAt") Instant updatedAt,
            @Param("version") long version);
}
