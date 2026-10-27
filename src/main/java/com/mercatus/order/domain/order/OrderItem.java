package com.mercatus.order.domain.order;

import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.shared.Money;
import java.util.Map;

public record OrderItem(String skuCode, String productName, Map<String, String> variationAttributes,
        long quantity, Money unitPrice, Money subtotal) {

    public OrderItem {
        var snapshot = new SkuSnapshot(skuCode, productName, variationAttributes, unitPrice);
        variationAttributes = snapshot.variationAttributes();

        if (!unitPrice.multiply(quantity).equals(subtotal)) {
            throw new DomainRuleException("Item subtotal must equal unit price times quantity");
        }
    }

    public static OrderItem create(SkuSnapshot sku, long quantity) {
        return new OrderItem(sku.skuCode(), sku.productName(), sku.variationAttributes(), quantity,
                sku.unitPrice(), sku.unitPrice().multiply(quantity));
    }
}
