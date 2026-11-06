package com.mercatus.order.integration.order;

import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderDependencyFailureIT extends OrderApiTestSupport {


    @ParameterizedTest
    @ValueSource(strings = {"/skus?code=A", "/customers/019a0000-0000-7000-8000-000000000001"})
    void dependencyNotFoundIs404AndDoesNotConsumeKey(String path) {
        remote.reply(path, 404, "private remote response");

        problem(post("key", request()), 404);

        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 429, 408})
    void technicalFailureIs503AndDoesNotConsumeKey(int status) {
        remote.reply("/skus?code=B", status, "customer@example.com secret");

        problem(post("key", request()), 503);

        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/skus?code=A", "/customers/019a0000-0000-7000-8000-000000000001"})
    void readTimeoutIs503AndDoesNotConsumeKey(String path) {
        remote.delay(path, 200, Map.of(), 2500);

        problem(post("key", request()), 503);

        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @ParameterizedTest
    @MethodSource("incompatibleSkus")
    void incompatibleCatalogResponseIs502(Object body) {
        remote.reply("/skus?code=A", 200, body);

        problem(post("key", request()), 502);

        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    static Stream<Object> incompatibleSkus() {
        var samples = new ArrayList<Object>(List.of("{", "null", Map.of(), Map.of("code", "other", "availableForSale", false)));

        for (String field : List.of("price", "currency", "productName", "variationAttributes", "availableForSale")) {
            var missing = RemoteStub.skuBody("A", "42.50");
            missing.remove(field);
            samples.add(missing);
        }

        for (String price : List.of("-1.00", "1.001", "100000000000000000.00")) {
            samples.add(RemoteStub.skuBody("A", price));
        }

        var wrongCurrency = RemoteStub.skuBody("A", "42.50");
        wrongCurrency.put("currency", "USD");
        samples.add(wrongCurrency);

        return samples.stream();
    }

    @Test
    void incompatibleCustomerResponseIs502() {
        for (String field : List.of("id", "name", "email", "status", "addresses")) {
            var customer = RemoteStub.customerBody();
            customer.remove(field);
            remote.reply("/customers/" + CUSTOMER, 200, customer);

            problem(post("key", request()), 502);
        }

        var customer = RemoteStub.customerBody();
        customer.put("addresses", List.of(Map.of("id", ADDRESS)));
        remote.reply("/customers/" + CUSTOMER, 200, customer);

        problem(post("key", request()), 502);

        assertCounts(0, 0, 0);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void numericCustomerStatusIs502AndDoesNotConsumeKey(int status) {
        var customer = RemoteStub.customerBody();
        customer.put("status", status);
        remote.reply("/customers/" + CUSTOMER, 200, customer);

        problem(post("key", request()), 502);
        assertCounts(0, 0, 0);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @Test
    void lookupErrorsAndOperationalEndpointsFollowContract() {
        problem(get("/orders/" + UUID.randomUUID()), 404);
        problem(get("/orders/not-a-uuid"), 400);
        problem(get("/orders/1-1-1-1-1"), 400);

        assertThat(get("/ping").status()).isEqualTo(200);
        assertThat(get("/actuator/health").status()).isEqualTo(200);

        Reply api = get("/v3/api-docs");

        assertThat(api.status()).isEqualTo(200);

        var tree = JSON.readTree(api.body());

        assertThat(tree.path("paths").has("/orders")).isTrue();
        assertThat(tree.path("paths").has("/orders/{id}")).isTrue();
        assertThat(tree.path("paths")
                .path("/orders")
                .path("post")
                .path("parameters").toString()).contains("Idempotency-Key");
        assertThat(tree.path("components")
                .path("schemas")
                .path("OrderResponse")
                .path("properties").has("history")).isTrue();

        var plain = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1")
                .build();
        String generated = plain.get()
                .uri("/ping")
                .exchange((req, res) -> res.getHeaders().getFirst("X-Correlation-Id"));

        assertThat(UUID.fromString(generated)).isNotNull();
    }
}
