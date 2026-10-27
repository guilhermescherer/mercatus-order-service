package com.mercatus.order.infrastructure.persistence.order;

import java.util.List;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.order;
import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    @Test
    void reconstructsImmutableAggregateAndCanonicalIdentityFromStoredJsonSnapshots() {
        var original = order();
        var entity = new OrderEntity();
        entity.id = original.id();
        entity.number = original.number();
        entity.idempotencyKey = original.idempotencyKey();
        entity.creationRequest = OrderMapper.json(original.creationRequest());
        entity.customerSnapshot = OrderMapper.json(original.customer());
        entity.shippingAddressSnapshot = OrderMapper.json(original.shippingAddress());
        entity.paymentMethod = original.paymentMethod().name();
        entity.total = original.total().amount();
        entity.currency = original.total().currency();
        entity.status = original.status().name();
        entity.createdAt = original.createdAt();
        entity.updatedAt = original.updatedAt();
        entity.version = original.version();

        entity.items = original.items()
                .stream()
                .map(item -> {
                    var stored = new OrderItemEntity();
                    stored.skuCode = item.skuCode();
                    stored.productName = item.productName();
                    stored.variationAttributes = OrderMapper.json(item.variationAttributes());
                    stored.quantity = item.quantity();
                    stored.unitPrice = item.unitPrice().amount();
                    stored.subtotal = item.subtotal().amount();
                    stored.currency = item.unitPrice().currency();

                    return stored;
                })
                .toList();

        var transition = new OrderTransitionEntity();
        transition.toStatus = "CREATED";
        transition.trigger = "ORDER_CREATED";
        transition.occurredAt = original.createdAt();
        transition.actor = "HTTP_REQUEST";
        entity.history = List.of(transition);

        assertThat(OrderMapper.toDomain(entity)).usingRecursiveComparison().isEqualTo(original);
    }
}
