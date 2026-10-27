package com.mercatus.order.application.order.port.in;

import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.PaymentMethod;
import java.util.List;
import java.util.UUID;

public interface CreateOrderUseCase {

    Result create(CreateOrderCommand command);

    record CreateOrderCommand(String idempotencyKey, UUID customerId, UUID addressId,
            PaymentMethod paymentMethod, List<Line> items) {

        public record Line(String skuCode, long quantity) {
        }
    }

    record Result(Order order, boolean created) {
    }
}
