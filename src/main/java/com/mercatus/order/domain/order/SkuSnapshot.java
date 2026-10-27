package com.mercatus.order.domain.order;

import com.mercatus.order.domain.shared.Money;
import java.util.Map;
import java.util.Objects;

public record SkuSnapshot(String skuCode, String productName, Map<String, String> variationAttributes,
        Money unitPrice) {

    public SkuSnapshot {
        SnapshotValidation.requireText(skuCode, productName);
        variationAttributes = Map.copyOf(variationAttributes);
        Objects.requireNonNull(unitPrice);
    }
}
