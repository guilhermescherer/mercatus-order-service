package com.mercatus.order.infrastructure.persistence.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "order_items")
class OrderItemEntity {

    @Id
    Long id;

    @Column(name = "order_id")
    UUID orderId;

    int position;
    String skuCode;
    String productName;

    @JdbcTypeCode(SqlTypes.JSON)
    String variationAttributes;
    long quantity;
    BigDecimal unitPrice;
    BigDecimal subtotal;
    String currency;

    protected OrderItemEntity() {
    }
}
