package com.mercatus.order.support;

import com.mercatus.order.application.order.port.in.CreateOrderUseCase.CreateOrderCommand;
import com.mercatus.order.domain.order.CustomerSnapshot;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.OrderItem;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.domain.order.ShippingAddressSnapshot;
import com.mercatus.order.domain.order.SkuSnapshot;
import com.mercatus.order.domain.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderFixture {

    public static final UUID CUSTOMER = UUID.fromString("019a0000-0000-7000-8000-000000000001");
    public static final UUID ADDRESS = UUID.fromString("019a0000-0000-7000-8000-000000000002");
    public static final Instant NOW = Instant.parse("2026-10-26T20:00:00Z");

    private OrderFixture() {
    }

    public static OrderCreationRequest request() {
        return new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("A", 2), new OrderCreationRequest.Line("B", 3)));
    }

    public static CreateOrderCommand command(String key) {
        return new CreateOrderCommand(key, CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new CreateOrderCommand.Line("A", 2), new CreateOrderCommand.Line("B", 3)));
    }

    public static CreateOrderCommand command(String key, OrderCreationRequest request) {
        return new CreateOrderCommand(key, request.customerId(), request.addressId(), request.paymentMethod(),
                request.items().stream()
                        .map(line -> new CreateOrderCommand.Line(line.skuCode(), line.quantity()))
                        .toList());
    }

    public static CustomerSnapshot customer() {
        return new CustomerSnapshot(CUSTOMER, "Example Customer", "customer@example.com");
    }

    public static ShippingAddressSnapshot address() {
        return new ShippingAddressSnapshot(ADDRESS, "01001000", "Example Street", "100", null,
                "Centro", "São Paulo", "SP", "BR");
    }

    public static OrderItem item(String sku, String price, long quantity) {
        return OrderItem.create(new SkuSnapshot(sku, "Example Product", Map.of("size", "M"),
                new Money(new BigDecimal(price), "BRL")), quantity);
    }

    public static Order order() {
        return Order.create(UUID.randomUUID(), "ORD-000000000001", "key", request(), customer(), address(),
                List.of(item("A", "42.50", 2), item("B", "10.00", 3)), NOW);
    }
}
