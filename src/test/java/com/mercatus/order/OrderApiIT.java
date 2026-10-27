package com.mercatus.order;

import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    static final RemoteStub remote = new RemoteStub();
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;
    RestClient client;

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("order.clients.catalog.base-url", remote::baseUrl);
        registry.add("order.clients.customer.base-url", remote::baseUrl);
        registry.add("order.clients.catalog.read-timeout", () -> "2s");
        registry.add("order.clients.customer.read-timeout", () -> "2s");
    }

    @BeforeEach
    void prepare() {
        jdbc.execute("TRUNCATE order_transitions, order_items, orders");

        remote.reset();

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory)
                .build();
    }

    @AfterAll
    static void closeRemote() {
        remote.close();
    }

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

    @Test
    void rollbackAfterRootAndItemsDoesNotConsumeKey() {
        jdbc.execute("ALTER TABLE order_transitions ADD CONSTRAINT reject_initial_transition CHECK (actor <> 'HTTP_REQUEST')");

        try {
            problem(post("key", request()), 409);

            assertCounts(0, 0, 0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'key'", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE order_transitions DROP CONSTRAINT reject_initial_transition");
        }

        assertThat(post("key", request()).status()).isEqualTo(201);
        assertCounts(1, 2, 1);
    }

    @Test
    void concurrentIdenticalRequestsProduceOneCreatedResponseAndOneHistory() throws Exception {
        List<Reply> responses = race(false);

        assertThat(responses.stream().filter(reply -> reply.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(reply -> reply.status() == 200)).hasSize(7);
        assertThat(responses.stream().map(reply -> reply.order().id()).distinct()).hasSize(1);
        assertCounts(1, 2, 1);
    }

    @Test
    void concurrentDifferentContentHasExactlyOneWinningContent() throws Exception {
        List<Reply> responses = race(true);

        assertThat(responses.stream().filter(reply -> reply.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(reply -> reply.status() == 200)).hasSize(3);
        assertThat(responses.stream().filter(reply -> reply.status() == 409)).hasSize(4);
        assertCounts(1, 2, 1);
    }

    private List<Reply> race(boolean differentContent) throws Exception {
        remote.catalogBarrier = new CyclicBarrier(8);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Reply>>();

            for (int i = 0; i < 8; i++) {
                var purchase = new OrderCreationRequest(CUSTOMER, ADDRESS,
                        differentContent && i % 2 == 0 ? PaymentMethod.CARD : PaymentMethod.PIX, request().items());
                futures.add(executor.submit(() -> {
                    start.await();

                    return post("race", purchase);
                }));
            }

            start.countDown();
            var replies = new ArrayList<Reply>();

            for (Future<Reply> future : futures) {
                replies.add(future.get(20, TimeUnit.SECONDS));
            }

            return replies;
        } finally {
            remote.catalogBarrier = null;
        }
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

    @Test
    void migrationAndDatabaseConstraintsProtectStoredPurchase() {
        post("key", request());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Long.class))
                .isEqualTo(1);

        for (String statement : List.of(
                "UPDATE order_items SET quantity = 0",
                "UPDATE order_items SET unit_price = -1",
                "UPDATE order_items SET subtotal = -1",
                "UPDATE order_items SET currency = 'USD'",
                "UPDATE orders SET total = -1",
                "UPDATE orders SET currency = 'USD'",
                "UPDATE orders SET idempotency_key = ''",
                "INSERT INTO order_items (order_id, position, sku_code, product_name, variation_attributes, quantity, unit_price, subtotal, currency) SELECT order_id, 99, sku_code, product_name, variation_attributes, quantity, unit_price, subtotal, currency FROM order_items LIMIT 1")) {
            assertThatThrownBy(() -> jdbc.execute(statement))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        assertCounts(1, 2, 1);
    }

    Reply post(String key, Object body) {
        var call = client.post()
                .uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Correlation-Id", "test-correlation");

        if (key != null) {
            call.header("Idempotency-Key", key);
        }

        return exchange(call.body(body));
    }

    Reply get(String path) {
        return exchange(client.get().uri(path));
    }

    static Reply exchange(RestClient.RequestHeadersSpec<?> request) {
        return request.exchange((req, res) -> new Reply(res.getStatusCode().value(), res.getHeaders().getFirst("Location"),
                new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8),
                res.getHeaders().getFirst("X-Correlation-Id"), res.getHeaders().getFirst("Content-Type")));
    }

    void problem(Reply reply, int status) {
        assertThat(reply.status()).as(reply.body()).isEqualTo(status);
        assertThat(reply.contentType()).startsWith("application/problem+json");
        assertThat(JSON.readTree(reply.body()).path("status").asInt()).isEqualTo(status);
        assertThat(reply.body())
                .doesNotContain("customer@example.com", "Example Street", "private remote", "secret", "idempotencyKey");
    }

    void assertCounts(long orders, long items, long history) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Long.class)).isEqualTo(orders);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Long.class)).isEqualTo(items);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_transitions", Long.class)).isEqualTo(history);
    }

    record Reply(int status, String location, String body, String correlation, String contentType) {

        OrderResponse order() {
            return JSON.readValue(body, OrderResponse.class);
        }
    }
}
