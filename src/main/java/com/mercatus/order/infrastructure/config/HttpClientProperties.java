package com.mercatus.order.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("order.clients")
public record HttpClientProperties(@Valid @NotNull Endpoint catalog, @Valid @NotNull Endpoint customer,
        @Valid @NotNull Timeouts identityProvider) {

    public record Endpoint(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

        public Endpoint {
            if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                    || !positive(connectTimeout) || !positive(readTimeout)) {
                throw new IllegalArgumentException("HTTP clients require an HTTP URL and positive timeouts");
            }
        }
    }

    public record Timeouts(@NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

        public Timeouts {
            if (!positive(connectTimeout) || !positive(readTimeout)) {
                throw new IllegalArgumentException("HTTP clients require positive timeouts");
            }
        }
    }

    private static boolean positive(Duration timeout) {
        return timeout != null && !timeout.isZero() && !timeout.isNegative();
    }
}
