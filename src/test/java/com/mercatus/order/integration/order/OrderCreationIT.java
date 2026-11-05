package com.mercatus.order.integration.order;

import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderCreationIT extends OrderApiTestSupport {


    @Test
    void createsQueriesAndReplaysPersistedPurchaseWithoutRemoteCalls() {
        Reply created = post("key", request());

        assertThat(created.status()).isEqualTo(201);

        OrderResponse order = created.order();

        assertThat(order.id().version()).isEqualTo(7);
        assertThat(order.number()).matches("ORD-[0-9]{12,}");
        assertThat(created.location()).isEqualTo("/api/v1/orders/" + order.id());
        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(order.cancellationReason()).isNull();
        assertThat(order.total().amount()).isEqualByComparingTo("115.00");
        assertThat(order.items()).hasSize(2);
        assertThat(order.customer().id()).isEqualTo(CUSTOMER);
        assertThat(order.shippingAddress().id()).isEqualTo(ADDRESS);
        assertThat(order.shippingAddress().country()).isEqualTo("BR");
        assertThat(order.shippingAddress().complement()).isNull();
        assertThat(order.history()).hasSize(1);
        assertThat(order.history().getFirst().actor()).isEqualTo("HTTP_REQUEST");
        assertThat(order.history().getFirst().occurredAt()).isEqualTo(order.createdAt());
        assertThat(order.updatedAt()).isEqualTo(order.createdAt());
        assertThat(order.version()).isZero();
        assertThat(remote.paths).containsExactly("/api/v1/customers/" + CUSTOMER, "/api/v1/skus?code=A", "/api/v1/skus?code=B");
        assertThat(remote.correlations).containsOnly("test-correlation");
        assertThat(created.correlation()).isEqualTo("test-correlation");

        var lines = new ArrayList<>(request().items());
        Collections.reverse(lines);
        remote.reply("/customers/" + CUSTOMER, 503, Map.of());
        remote.reply("/skus?code=A", 503, Map.of());
        int calls = remote.paths.size();

        Reply replay = post("key", Map.of("customerId", CUSTOMER, "addressId", ADDRESS, "paymentMethod", "PIX", "items", lines));

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.location()).isEqualTo(created.location());
        assertThat(replay.order()).isEqualTo(order);
        assertThat(get("/orders/" + order.id()).order()).isEqualTo(order);
        assertThat(remote.paths).hasSize(calls);
        assertCounts(1, 2, 1);
        assertThat(created.body()).doesNotContain("idempotencyKey", "creationRequest");
    }

    @Test
    void sourceChangesAffectOnlyNewPurchasesAndKeysAreCaseSensitive() {
        var original = post("Key", request()).order();

        var changedSku = RemoteStub.skuBody("A", "50.00");
        changedSku.put("productName", "Renamed Product");
        changedSku.put("variationAttributes", Map.of("size", "L"));
        remote.reply("/skus?code=A", 200, changedSku);

        var changedCustomer = RemoteStub.customerBody();
        changedCustomer.put("name", "Renamed Customer");
        changedCustomer.put("email", "changed@example.com");
        changedCustomer.put("addresses", List.of(Map.of("id", ADDRESS, "zipCode", "02002000", "street", "New Street",
                "number", "200", "complement", "Suite", "district", "New District", "city", "New City", "state", "RJ", "country", "BR")));
        remote.reply("/customers/" + CUSTOMER, 200, changedCustomer);

        assertThat(get("/orders/" + original.id()).order()).isEqualTo(original);
        assertThat(post("Key", request()).order()).isEqualTo(original);

        var newPurchase = post("key", request()).order();

        assertThat(newPurchase.id()).isNotEqualTo(original.id());
        assertThat(newPurchase.number()).isNotEqualTo(original.number());
        assertThat(newPurchase.total().amount()).isEqualByComparingTo("130.00");
        assertThat(newPurchase.items().getFirst().productName()).isEqualTo("Renamed Product");
        assertThat(newPurchase.items().getFirst().variationAttributes()).containsEntry("size", "L");
        assertThat(newPurchase.customer().name()).isEqualTo("Renamed Customer");
        assertThat(newPurchase.shippingAddress().street()).isEqualTo("New Street");
        assertCounts(2, 4, 2);
    }

    @Test
    void differentContentConflictsGloballyWithoutRemoteCalls() {
        post("key", request());
        int calls = remote.paths.size();
        var different = new OrderCreationRequest(UUID.randomUUID(), ADDRESS, PaymentMethod.PIX, request().items());

        problem(post("key", different), 409);
        problem(post("key", new OrderCreationRequest(CUSTOMER, UUID.randomUUID(), PaymentMethod.PIX, request().items())), 409);
        problem(post("key", new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.CARD, request().items())), 409);
        problem(post("key", new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("A", 3)))), 409);

        assertThat(remote.paths).hasSize(calls);
        assertCounts(1, 2, 1);
    }
}
