package com.mercatus.order.application.order.port.out;

import com.mercatus.order.domain.order.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {

    Optional<Order> findById(UUID id);
    Optional<Order> findByKey(String key);
    String nextNumber();
    boolean tryCreate(Order order);
}
