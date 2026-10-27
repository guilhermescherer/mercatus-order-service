package com.mercatus.order.application.order.usecase;

import com.mercatus.order.application.exception.BusinessRuleException;
import com.mercatus.order.application.exception.StateConflictException;
import com.mercatus.order.application.order.port.in.CreateOrderUseCase;
import com.mercatus.order.application.order.port.out.CatalogRepository;
import com.mercatus.order.application.order.port.out.CustomerRepository;
import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.domain.order.CustomerProfile;
import com.mercatus.order.domain.order.Order;
import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.OrderCreationRequest.Line;
import com.mercatus.order.domain.order.OrderItem;
import com.mercatus.order.domain.order.ShippingAddressSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static java.time.temporal.ChronoUnit.MICROS;

@Service
public class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final CustomerRepository customers;
    private final Clock clock;
    private final Supplier<UUID> identifiers;

    public CreateOrderService(OrderRepository orders, CatalogRepository catalog, CustomerRepository customers,
                              Clock clock, Supplier<UUID> identifiers
    ) {
        this.orders = orders;
        this.catalog = catalog;
        this.customers = customers;
        this.clock = clock;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Result create(CreateOrderCommand command) {
        OrderCreationRequest.validateKey(command.idempotencyKey());

        String key = command.idempotencyKey();
        OrderCreationRequest request = new OrderCreationRequest(
                command.customerId(), command.addressId(), command.paymentMethod(),
                command.items().stream()
                        .map(line -> new Line(line.skuCode(), line.quantity()))
                        .toList());

        var existing = orders.findByKey(key);

        if (existing.isPresent()) {
            return replay(existing.get(), request);
        }

        var customer = loadActiveCustomer(request.customerId());
        var shippingAddress = selectShippingAddress(customer, request.addressId());
        var items = createItems(request.items());

        var order = Order.create(identifiers.get(), orders.nextNumber(), key, request, customer.customer(), shippingAddress,
                items, clock.instant().truncatedTo(MICROS));

        if (orders.tryCreate(order)) {
            return new Result(order, true);
        }

        return replay(loadWinningOrder(key), request);
    }

    private CustomerProfile loadActiveCustomer(UUID customerId) {
        var customer = customers.getById(customerId);

        if (!customer.active()) {
            throw new BusinessRuleException("Customer is inactive");
        }

        return customer;
    }

    private ShippingAddressSnapshot selectShippingAddress(CustomerProfile customer, UUID addressId) {
        return customer.addresses().stream()
                .filter(address -> address.id().equals(addressId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("Address does not belong to customer"));
    }

    private List<OrderItem> createItems(List<Line> lines) {
        return lines.stream()
                .map(this::createItem)
                .toList();
    }

    private OrderItem createItem(Line line) {
        var sku = catalog.getByCode(line.skuCode());

        if (!sku.availableForSale()) {
            throw new BusinessRuleException("SKU is unavailable for sale");
        }

        return OrderItem.create(sku.snapshot(), line.quantity());
    }

    private Order loadWinningOrder(String key) {
        return orders.findByKey(key)
                .orElseThrow(() -> new IllegalStateException(
                        "Winning order was not found after idempotency conflict"
                ));
    }

    private Result replay(Order order, OrderCreationRequest request) {
        if (!order.creationRequest().equals(request)) {
            throw new StateConflictException("Idempotency key already used for different content");
        }

        return new Result(order, false);
    }
}
