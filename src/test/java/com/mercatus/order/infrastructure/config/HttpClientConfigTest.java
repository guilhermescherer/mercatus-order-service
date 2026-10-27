package com.mercatus.order.infrastructure.config;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpClientConfigTest {

    @Test
    void bothConfiguredClientsPropagateRequestCorrelation() {
        var endpoint = new HttpClientProperties.Endpoint(URI.create("http://dependency/api/v1"),
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        var properties = new HttpClientProperties(endpoint, endpoint);
        var config = new HttpClientConfig();

        for (var client : java.util.List.of(config.catalogClient(properties), config.customerClient(properties))) {
            var builder = client.mutate();
            var server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("http://dependency/api/v1/resource"))
                    .andExpect(header("X-Correlation-Id", "test-correlation"))
                    .andRespond(withSuccess());
            MDC.put("correlationId", "test-correlation");

            try {
                builder.build().get()
                        .uri("/resource")
                        .retrieve()
                        .toBodilessEntity();
                server.verify();
            } finally {
                MDC.remove("correlationId");
            }
        }
    }

    @Test
    void refusesInfiniteOrNegativeTimeoutAndNonHttpUrl() {
        assertThatThrownBy(() -> new HttpClientProperties.Endpoint(URI.create("http://dependency"), Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpClientProperties.Endpoint(URI.create("http://dependency"), Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpClientProperties.Endpoint(URI.create("file:///tmp/resource"), Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
