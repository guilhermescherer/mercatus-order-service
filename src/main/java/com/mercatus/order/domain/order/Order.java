package com.mercatus.order.domain.order;

import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.shared.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Order {

    private final UUID id;
    private final String number;
    private final String idempotencyKey;
    private final OrderCreationRequest creationRequest;
    private final CustomerSnapshot customer;
    private final ShippingAddressSnapshot shippingAddress;
    private final PaymentMethod paymentMethod;
    private final List<OrderItem> items;
    private final Money total;
    private final OrderStatus status;
    private final String cancellationReason;
    private final List<OrderTransition> history;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final long version;

    private Order(UUID id, String number, String idempotencyKey, OrderCreationRequest creationRequest,
            CustomerSnapshot customer, ShippingAddressSnapshot shippingAddress, PaymentMethod paymentMethod,
            List<OrderItem> items, Money total, OrderStatus status, String cancellationReason,
            List<OrderTransition> history, Instant createdAt, Instant updatedAt, long version) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        OrderCreationRequest.validateKey(idempotencyKey);

        if (number == null || !number.matches("ORD-[0-9]{12,}")) {
            throw new DomainRuleException("Invalid order number");
        }

        List<OrderItem> copiedItems = List.copyOf(items);
        List<OrderTransition> copiedHistory = List.copyOf(history);

        var identity = new OrderCreationRequest(
                customer.id(), shippingAddress.id(), paymentMethod,
                copiedItems.stream()
                        .map(item -> new OrderCreationRequest.Line(item.skuCode(), item.quantity()))
                        .toList());

        if (!identity.equals(creationRequest)) {
            throw new DomainRuleException("Order must match its creation request");
        }

        var calculated = copiedItems.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.zero(), Money::add);

        if (!calculated.equals(total)) {
            throw new DomainRuleException("Order total must equal item subtotals");
        }

        if (status == null || copiedHistory.isEmpty()) {
            throw new DomainRuleException("Order requires a status and history");
        }

        this.id = id;
        this.number = number;
        this.idempotencyKey = idempotencyKey;
        this.creationRequest = creationRequest;
        this.customer = customer;
        this.shippingAddress = shippingAddress;
        this.paymentMethod = paymentMethod;
        this.items = copiedItems;
        this.total = total;
        this.status = status;
        this.cancellationReason = cancellationReason;
        this.history = copiedHistory;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public static Order create(UUID id, String number, String key, OrderCreationRequest request,
            CustomerSnapshot customer, ShippingAddressSnapshot address, List<OrderItem> items, Instant now) {
        Money total = items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.zero(), Money::add);

        return new Order(id, number, key, request, customer, address, request.paymentMethod(), items, total,
                OrderStatus.CREATED, null, List.of(OrderTransition.initial(now)), now, now, 0);
    }

    public static Order reconstitute(UUID id, String number, String key, OrderCreationRequest request,
            CustomerSnapshot customer, ShippingAddressSnapshot address, PaymentMethod paymentMethod,
            List<OrderItem> items, Money total, OrderStatus status, String cancellationReason,
            List<OrderTransition> history, Instant createdAt, Instant updatedAt, long version) {
        return new Order(id, number, key, request, customer, address, paymentMethod, items, total, status,
                cancellationReason, history, createdAt, updatedAt, version);
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public OrderCreationRequest creationRequest() {
        return creationRequest;
    }

    public CustomerSnapshot customer() {
        return customer;
    }

    public ShippingAddressSnapshot shippingAddress() {
        return shippingAddress;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public List<OrderItem> items() {
        return items;
    }

    public Money total() {
        return total;
    }

    public OrderStatus status() {
        return status;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public List<OrderTransition> history() {
        return history;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
