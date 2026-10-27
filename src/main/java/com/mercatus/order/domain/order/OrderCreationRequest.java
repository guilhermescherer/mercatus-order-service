package com.mercatus.order.domain.order;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record OrderCreationRequest(UUID customerId, UUID addressId, PaymentMethod paymentMethod, List<Line> items) {

    public OrderCreationRequest {
        if (customerId == null || addressId == null || paymentMethod == null || items == null || items.isEmpty()) {
            throw new DomainRuleException("Order requires a customer, address, payment method and items");
        }

        var codes = new HashSet<String>();

        for (Line line : items) {
            if (line == null || !codes.add(line.skuCode())) {
                throw new DomainRuleException("Order must not contain duplicate SKUs or missing items");
            }
        }

        items = items.stream()
                .sorted(Comparator.comparing(Line::skuCode))
                .toList();
    }

    public static void validateKey(String key) {
        if (key == null || key.isBlank() || !key.equals(key.strip()) || key.length() > 128) {
            throw new IllegalArgumentException("Invalid Idempotency-Key");
        }
    }

    public record Line(String skuCode, long quantity) {

        public Line {
            if (skuCode == null || skuCode.isBlank() || quantity <= 0) {
                throw new DomainRuleException("Order items require a SKU and a positive quantity");
            }
        }
    }
}
