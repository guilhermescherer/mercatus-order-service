package com.mercatus.order.integration.order;

import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class OrderApiTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
    static final RemoteStub remote = new RemoteStub();
    static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(remote::close));
    }

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
        jdbc.execute("TRUNCATE order_saga, order_transitions, order_items, orders");

        remote.reset();

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port + "/api/v1")
                .requestFactory(factory)
                .build();
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
