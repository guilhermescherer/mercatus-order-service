package com.mercatus.order.infrastructure.persistence.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_transitions")
class OrderTransitionEntity {

    @Id
    Long id;

    @Column(name = "order_id")
    UUID orderId;

    int position;
    String fromStatus;
    String toStatus;
    String trigger;
    Instant occurredAt;
    String actor;

    protected OrderTransitionEntity() {
    }
}
