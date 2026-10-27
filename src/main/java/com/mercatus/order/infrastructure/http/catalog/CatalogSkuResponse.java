package com.mercatus.order.infrastructure.http.catalog;

import java.math.BigDecimal;
import java.util.Map;

record CatalogSkuResponse(String code, BigDecimal price, String currency, Boolean availableForSale,
        String productName, Map<String, String> variationAttributes) {
}
