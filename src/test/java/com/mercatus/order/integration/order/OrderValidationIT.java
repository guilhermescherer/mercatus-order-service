package com.mercatus.order.integration.order;

import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.support.RemoteStub;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderValidationIT extends OrderApiTestSupport {


    @ParameterizedTest
    @MethodSource("invalidBodies")
    void rejectsInvalidInputWithoutCallingDependencies(String body) {
        problem(post("key", body), 400);

        assertThat(remote.paths).isEmpty();
        assertCounts(0, 0, 0);
    }

    static Stream<String> invalidBodies() {
        String base = JSON.writeValueAsString(request());

        return Stream.of("{", "null", "{}", base.replace("\"PIX\"", "\"CASH\""),
                base.replace("\"quantity\":2", "\"quantity\":0"),
                base.replace("\"quantity\":2", "\"quantity\":-1"),
                base.replace("\"quantity\":2", "\"quantity\":1.5"),
                base.replace("\"quantity\":2", "\"quantity\":\"2\""),
                base.replace("\"quantity\":2", "\"quantity\":null"),
                base.replace("\"quantity\":2", "\"quantity\":9223372036854775808"),
                base.replace("\"skuCode\":\"A\"", "\"skuCode\":42"),
                base.replace("\"skuCode\":\"A\"", "\"skuCode\":\" \""),
                base.replace("\"skuCode\":\"A\"", "\"price\":42,\"skuCode\":\"A\""),
                base.replace("\"paymentMethod\":\"PIX\",", ""),
                base.replace(CUSTOMER.toString(), "invalid-uuid"),
                "{\"total\":0," + base.substring(1),
                JSON.writeValueAsString(Map.of("customerId", CUSTOMER, "addressId", ADDRESS, "paymentMethod", "PIX", "items", List.of())),
                "{\"customerId\":null,\"addressId\":\"" + ADDRESS + "\",\"paymentMethod\":\"PIX\",\"items\":[null]}");
    }

    @Test
    void requiresValidKeyAndRejectsDuplicateSkusAsBusinessRule() {
        problem(post(null, request()), 400);
        problem(post("", request()), 400);
        problem(post("x".repeat(129), request()), 400);
        var duplicate = Map.of("customerId", CUSTOMER, "addressId", ADDRESS, "paymentMethod", "PIX", "items",
                List.of(Map.of("skuCode", "A", "quantity", 1), Map.of("skuCode", "A", "quantity", 2)));

        problem(post("key", duplicate), 422);

        assertThat(remote.paths).isEmpty();
    }

    @Test
    void rejectsInactiveCustomerForeignAddressAndUnavailableSku() {
        var customer = RemoteStub.customerBody();
        customer.put("status", "INACTIVE");
        remote.reply("/customers/" + CUSTOMER, 200, customer);

        problem(post("key", request()), 422);

        remote.reset();

        var foreign = new OrderCreationRequest(CUSTOMER, UUID.randomUUID(), PaymentMethod.PIX, request().items());

        problem(post("key", foreign), 422);
        remote.reply("/skus?code=A", 200, Map.of("code", "A", "availableForSale", false));

        problem(post("key", request()), 422);

        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @Test
    void monetaryOverflowIsRejectedBeforePersistenceAndZeroIsAllowed() {
        remote.reply("/skus?code=A", 200, RemoteStub.skuBody("A", "99999999999999999.99"));

        problem(post("key", request()), 422);
        var totalOverflow = new OrderCreationRequest(CUSTOMER, ADDRESS, PaymentMethod.PIX,
                List.of(new OrderCreationRequest.Line("A", 1), new OrderCreationRequest.Line("B", 1)));

        problem(post("key", totalOverflow), 422);

        assertCounts(0, 0, 0);
        remote.reply("/skus?code=A", 200, RemoteStub.skuBody("A", "0.00"));
        remote.reply("/skus?code=B", 200, RemoteStub.skuBody("B", "0.00"));

        assertThat(post("key", request()).order().total().amount()).isZero();
    }
}
