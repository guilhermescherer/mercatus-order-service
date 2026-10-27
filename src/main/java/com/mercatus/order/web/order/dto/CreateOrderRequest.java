package com.mercatus.order.web.order.dto;

import com.mercatus.order.application.order.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.order.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(@NotNull UUID customerId, @NotNull UUID addressId,
        @NotNull PaymentMethod paymentMethod, @NotEmpty List<@NotNull @Valid OrderLineRequest> items) {

    public CreateOrderCommand toCommand(String idempotencyKey) {
        var codes = new HashSet<String>();

        for (OrderLineRequest item : items) {
            if (!codes.add(item.skuCode())) {
                throw new DomainRuleException("Order must not contain duplicate SKUs");
            }
        }

        return new CreateOrderCommand(
                idempotencyKey, customerId, addressId, paymentMethod,
                items.stream()
                        .map(item -> new CreateOrderCommand.Line(item.skuCode(), item.quantity()))
                        .toList()
        );
    }
}
