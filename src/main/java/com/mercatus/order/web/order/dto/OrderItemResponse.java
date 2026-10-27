package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.order.OrderItem;
import java.util.Map;

public record OrderItemResponse(String skuCode, String productName, Map<String, String> variationAttributes,
        long quantity, MoneyResponse unitPrice, MoneyResponse subtotal) {

    static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(item.skuCode(), item.productName(), item.variationAttributes(), item.quantity(),
                MoneyResponse.from(item.unitPrice()), MoneyResponse.from(item.subtotal()));
    }
}
