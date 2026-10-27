package com.mercatus.order.infrastructure.persistence.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders")
class OrderEntity {

    @Id
    UUID id;
    String number;

    @Column(name = "idempotency_key")
    String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "creation_request")
    String creationRequest;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_snapshot")
    String customerSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shipping_address_snapshot")
    String shippingAddressSnapshot;
    String paymentMethod;
    String status;
    String cancellationReason;
    BigDecimal total;
    String currency;
    Instant createdAt;
    Instant updatedAt;

    @Version
    long version;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    @OrderBy("position")
    List<OrderItemEntity> items;

    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", insertable = false, updatable = false)
    @OrderBy("position")
    List<OrderTransitionEntity> history;

    protected OrderEntity() {
    }
}
