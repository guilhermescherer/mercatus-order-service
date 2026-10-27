package com.mercatus.order.infrastructure.persistence.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

interface OrderTransitionJpaRepository extends JpaRepository<OrderTransitionEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO order_transitions (order_id, position, from_status, to_status, trigger, occurred_at, actor)
            VALUES (:orderId, :position, :fromStatus, :toStatus, :trigger, :occurred, :actor)
            """, nativeQuery = true)
    void insert(
            @Param("orderId") UUID orderId,
            @Param("position") int position,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("trigger") String trigger,
            @Param("occurred") Instant occurred,
            @Param("actor") String actor
    );
}
