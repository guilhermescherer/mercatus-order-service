package com.mercatus.order.domain.order;

import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderTest {

    @Test
    void createsImmutablePurchaseWithExactTotalAndInitialHistory() {
        var order = order();

        assertThat(order.total().amount()).isEqualByComparingTo("115.00");
        assertThat(order.items().getFirst().subtotal().amount()).isEqualByComparingTo("85.00");
        assertThat(order.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(order.cancellationReason()).isNull();
        assertThat(order.createdAt()).isEqualTo(NOW);
        assertThat(order.updatedAt()).isEqualTo(NOW);
        assertThat(order.version()).isZero();
        assertThat(order.history()).containsExactly(new OrderTransition(null, OrderStatus.CREATED,
                "ORDER_CREATED", NOW, "HTTP_REQUEST"));
        assertThatThrownBy(() -> order.items().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> order.history().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotsAndCollectionsAreDefensivelyCopied() {
        var attributes = new HashMap<>(Map.of("color", "blue"));
        var sku = new SkuSnapshot("A", "Original", attributes, Money.zero());
        var item = OrderItem.create(sku, 2);
        var items = new ArrayList<>(List.of(item, item("B", "0.00", 3)));
        var order = Order.create(UUID.randomUUID(), "ORD-000000000001", "key", request(), customer(), address(), items, NOW);
        attributes.put("color", "red");
        items.clear();

        assertThat(order.items()).hasSize(2);
        assertThat(order.items().getFirst().variationAttributes()).containsEntry("color", "blue");
        assertThat(order.total()).isEqualTo(Money.zero());
        assertThatThrownBy(() -> item.variationAttributes().put("size", "L"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsEmptyOrderAndDuplicateSkuBeforeCanonicalization() {
        assertThatThrownBy(() -> new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX, List.of()))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("A", 1), new OrderCreationRequest.Line("A", 2))))
                .isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "ORD-000000000001", "key", request(),
                customer(), address(), List.of(), NOW)).isInstanceOf(DomainRuleException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveQuantity(long quantity) {
        assertThatThrownBy(() -> new OrderCreationRequest.Line("A", quantity)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> item("A", "1.00", quantity)).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void canonicalIdentityIgnoresItemOrderAndIncludesEveryPurchaseField() {
        var original = request();
        var reversed = new ArrayList<>(original.items());
        java.util.Collections.reverse(reversed);

        assertThat(new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX, reversed)).isEqualTo(original);
        reversed.clear();

        assertThat(original.items()).hasSize(2);
        assertThat(new OrderCreationRequest(UUID.randomUUID(), ADDRESS, PaymentMethod.PIX, original.items()))
                .isNotEqualTo(original);
        assertThat(new OrderCreationRequest(CUSTOMER, UUID.randomUUID(), PaymentMethod.PIX, original.items()))
                .isNotEqualTo(original);
        assertThat(new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.CARD, original.items()))
                .isNotEqualTo(original);
        assertThat(new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("A", 3), new OrderCreationRequest.Line("B", 3))))
                        .isNotEqualTo(original);
        assertThat(new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("a", 2), new OrderCreationRequest.Line("B", 3))))
                        .isNotEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " key", "key ", "\tkey", "key\n"})
    void rejectsInvalidIdempotencyKeys(String key) {
        assertThatThrownBy(() -> OrderCreationRequest.validateKey(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enforcesKeyLengthAndDoesNotNormalizeValidKeys() {
        assertThatCode(() -> OrderCreationRequest.validateKey("a".repeat(128))).doesNotThrowAnyException();
        assertThatCode(() -> OrderCreationRequest.validateKey("A key" )).doesNotThrowAnyException();
        assertThatThrownBy(() -> OrderCreationRequest.validateKey("a".repeat(129)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderCreationRequest.validateKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSnapshotsThatDoNotMatchCreationIdentity() {
        assertThatThrownBy(() -> Order.create(UUID.randomUUID(), "ORD-000000000001", "key", request(), customer(),
                address(), List.of(item("A", "42.50", 1), item("B", "10.00", 3)), NOW))
                .isInstanceOf(DomainRuleException.class);
    }

    @Test
    void rejectsInconsistentSubtotal() {
        assertThatThrownBy(() -> new OrderItem("A", "Product", Map.of(), 2,
                new Money(new BigDecimal("42.50"), "BRL"), Money.zero())).isInstanceOf(DomainRuleException.class);
    }
}
