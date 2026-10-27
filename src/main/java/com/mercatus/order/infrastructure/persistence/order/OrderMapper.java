package com.mercatus.order.infrastructure.persistence.order;

import com.mercatus.order.domain.order.CustomerSnapshot;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.OrderItem;
import com.mercatus.order.domain.order.OrderStatus;
import com.mercatus.order.domain.order.OrderTransition;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.domain.order.ShippingAddressSnapshot;
import com.mercatus.order.domain.shared.Money;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class OrderMapper {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OrderMapper() {
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    static Order toDomain(OrderEntity entity) {
        return Order.reconstitute(
                entity.id, entity.number, entity.idempotencyKey,
                JSON.readValue(entity.creationRequest, OrderCreationRequest.class),
                JSON.readValue(entity.customerSnapshot, CustomerSnapshot.class),
                JSON.readValue(entity.shippingAddressSnapshot, ShippingAddressSnapshot.class),
                PaymentMethod.valueOf(entity.paymentMethod),
                entity.items.stream()
                        .map(OrderMapper::toDomain)
                        .toList(),
                new Money(entity.total, entity.currency), OrderStatus.valueOf(entity.status), entity.cancellationReason,
                entity.history.stream()
                        .map(OrderMapper::toDomain)
                        .toList(),
                entity.createdAt, entity.updatedAt, entity.version
        );
    }

    private static OrderItem toDomain(OrderItemEntity item) {
        Map<String, String> attributes = JSON.readValue(item.variationAttributes, new TypeReference<>() {});

        return new OrderItem(item.skuCode, item.productName, attributes, item.quantity,
                new Money(item.unitPrice, item.currency), new Money(item.subtotal, item.currency));
    }

    private static OrderTransition toDomain(OrderTransitionEntity transition) {
        return new OrderTransition(transition.fromStatus == null ? null : OrderStatus.valueOf(transition.fromStatus),
                OrderStatus.valueOf(transition.toStatus), transition.trigger, transition.occurredAt, transition.actor);
    }
}
