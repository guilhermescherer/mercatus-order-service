package com.mercatus.order.application.order.port.in;

import com.mercatus.order.domain.order.Order;
import java.util.UUID;

public interface CancelOrderUseCase {

    Result cancel(UUID orderId);

    record Result(Order order, boolean completed) {
    }
}
