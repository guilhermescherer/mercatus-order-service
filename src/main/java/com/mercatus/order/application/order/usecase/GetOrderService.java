package com.mercatus.order.application.order.usecase;

import com.mercatus.order.application.exception.ResourceNotFoundException;
import com.mercatus.order.application.order.port.in.GetOrderUseCase;
import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.domain.order.Order;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetOrderService implements GetOrderUseCase {

    private final OrderRepository orders;

    public GetOrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Order get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }
}
