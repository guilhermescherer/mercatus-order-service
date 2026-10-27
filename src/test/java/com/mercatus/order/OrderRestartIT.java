package com.mercatus.order;

import com.mercatus.order.support.RemoteStub;
import com.mercatus.order.web.order.dto.OrderResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OrderRestartIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Test
    void confirmedKeyAndSnapshotsSurviveApplicationRestartWithoutDependencies() {
        try (var remote = new RemoteStub()) {
            OrderResponse original;

            try (var first = start(remote)) {
                var response = client(first).post()
                        .uri("/orders")
                        .header("Idempotency-Key", "persistent-key")
                        .body(request())
                        .retrieve()
                        .toEntity(OrderResponse.class);

                assertThat(response.getStatusCode().value()).isEqualTo(201);
                original = response.getBody();
            }

            remote.reply("/customers/" + CUSTOMER, 503, Map.of());
            remote.reply("/skus?code=A", 503, Map.of());
            int calls = remote.paths.size();

            try (var restarted = start(remote)) {
                var response = client(restarted).post()
                        .uri("/orders")
                        .header("Idempotency-Key", "persistent-key")
                        .body(request())
                        .retrieve()
                        .toEntity(OrderResponse.class);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(response.getBody()).isEqualTo(original);
                assertThat(response.getHeaders().getFirst("Location")).isEqualTo("/api/v1/orders/" + original.id());
                assertThat(client(restarted).get()
                        .uri("/orders/" + original.id())
                        .retrieve()
                        .body(OrderResponse.class))
                        .isEqualTo(original);
                assertThat(remote.paths).hasSize(calls);
            }
        }
    }

    private ConfigurableApplicationContext start(RemoteStub remote) {
        return new SpringApplicationBuilder(OrderApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--order.clients.catalog.base-url=" + remote.baseUrl(),
                "--order.clients.customer.base-url=" + remote.baseUrl(),
                "--spring.main.banner-mode=off");
    }

    private RestClient client(ConfigurableApplicationContext context) {
        return RestClient.builder()
                .baseUrl("http://localhost:" + context.getEnvironment().getProperty("local.server.port")
                        + "/api/v1")
                .build();
    }
}
