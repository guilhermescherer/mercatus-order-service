package com.mercatus.order.infrastructure.persistence.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.UUID;

interface OrderItemJpaRepository extends JpaRepository<OrderItemEntity, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO order_items (order_id, position, sku_code, product_name, variation_attributes,
                quantity, unit_price, subtotal, currency)
            VALUES (:orderId, :position, :sku, :name, CAST(:attributes AS jsonb), :quantity, :price, :subtotal, :currency)
            """, nativeQuery = true)
    void insert(
            @Param("orderId") UUID orderId,
            @Param("position") int position,
            @Param("sku") String sku,
            @Param("name") String name,
            @Param("attributes") String attributes,
            @Param("quantity") long quantity,
            @Param("price") BigDecimal price,
            @Param("subtotal") BigDecimal subtotal,
            @Param("currency") String currency
    );
}
