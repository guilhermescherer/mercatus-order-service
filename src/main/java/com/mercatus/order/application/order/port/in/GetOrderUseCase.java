package com.mercatus.order.application.order.port.in;

import com.mercatus.order.domain.order.Order;
import java.util.UUID;

public interface GetOrderUseCase {

    Order get(UUID id);
}
