package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.order.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(UUID id, String number, CustomerSnapshotResponse customer,
        ShippingAddressResponse shippingAddress, String paymentMethod, List<OrderItemResponse> items,
        MoneyResponse total, String status, String cancellationReason, List<OrderTransitionResponse> history,
        Instant createdAt, Instant updatedAt, long version) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.id(), order.number(), CustomerSnapshotResponse.from(order.customer()),
                ShippingAddressResponse.from(order.shippingAddress()), order.paymentMethod().name(),
                order.items()
                        .stream()
                        .map(OrderItemResponse::from)
                        .toList(),
                MoneyResponse.from(order.total()),
                order.status().name(), order.cancellationReason(),
                order.history()
                        .stream()
                        .map(OrderTransitionResponse::from)
                        .toList(),
                order.createdAt(), order.updatedAt(), order.version()
        );
    }
}
