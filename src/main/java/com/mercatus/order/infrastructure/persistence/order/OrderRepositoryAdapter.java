package com.mercatus.order.infrastructure.persistence.order;

import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.domain.order.Order;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
class OrderRepositoryAdapter implements OrderRepository {

    private final OrderJpaRepository orderRepository;
    private final OrderItemJpaRepository itemsRepository;
    private final OrderTransitionJpaRepository transitionsRepository;

    OrderRepositoryAdapter(OrderJpaRepository orderRepository, OrderItemJpaRepository itemsRepository,
                           OrderTransitionJpaRepository transitionsRepository
    ) {
        this.orderRepository = orderRepository;
        this.itemsRepository = itemsRepository;
        this.transitionsRepository = transitionsRepository;
    }

    @Override
    public Optional<Order> findById(UUID id) {
        return orderRepository.findById(id).map(OrderMapper::toDomain);
    }

    @Override
    public Optional<Order> findByKey(String key) {
        return orderRepository.findByIdempotencyKey(key).map(OrderMapper::toDomain);
    }

    @Override
    public String nextNumber() {
        long value = orderRepository.nextNumber();

        return "ORD-" + String.format(Locale.ROOT, "%012d", value);
    }

    @Override
    public boolean tryCreate(Order order) {
        int inserted = orderRepository.insertIfAbsent(
                order.id(), order.number(), order.idempotencyKey(),
                OrderMapper.json(order.creationRequest()),
                OrderMapper.json(order.customer()),
                OrderMapper.json(order.shippingAddress()),
                order.paymentMethod().name(), order.status().name(),
                order.total().amount(), order.total().currency(),
                order.createdAt(), order.updatedAt(), order.version()
        );

        if (inserted == 0) {
            return false;
        }

        for (int position = 0; position < order.items().size(); position++) {
            var item = order.items().get(position);

            itemsRepository.insert(
                    order.id(), position, item.skuCode(), item.productName(),
                    OrderMapper.json(item.variationAttributes()),
                    item.quantity(), item.unitPrice().amount(), item.subtotal().amount(), item.unitPrice().currency()
            );
        }

        for (int position = 0; position < order.history().size(); position++) {
            var transition = order.history().get(position);

            transitionsRepository.insert(
                    order.id(), position,
                    transition.fromStatus() == null ? null : transition.fromStatus().name(),
                    transition.toStatus().name(), transition.trigger(), transition.occurredAt(), transition.actor()
            );
        }

        return true;
    }
}
