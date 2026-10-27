package com.mercatus.order.infrastructure.persistence.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface OrderJpaRepository extends JpaRepository<OrderEntity, UUID> {

    Optional<OrderEntity> findByIdempotencyKey(String key);

    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextNumber();

    @Modifying
    @Query(value = """
            INSERT INTO orders (id, number, idempotency_key, creation_request, customer_snapshot,
                shipping_address_snapshot, payment_method, status, cancellation_reason,
                total, currency, created_at, updated_at, version)
            VALUES (:id, :number, :key, CAST(:request AS jsonb), CAST(:customer AS jsonb),
                CAST(:address AS jsonb), :payment, :status, NULL, :total, :currency, :created, :updated, :version)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("number") String number,
            @Param("key") String key,
            @Param("request") String request,
            @Param("customer") String customer,
            @Param("address") String address,
            @Param("payment") String payment,
            @Param("status") String status,
            @Param("total") BigDecimal total,
            @Param("currency") String currency,
            @Param("created") Instant created,
            @Param("updated") Instant updated,
            @Param("version") long version
    );
}
