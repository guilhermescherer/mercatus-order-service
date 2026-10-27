package com.mercatus.order.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("order.clients")
public record HttpClientProperties(@Valid @NotNull Endpoint catalog, @Valid @NotNull Endpoint customer) {

    public record Endpoint(@NotNull URI baseUrl, @NotNull Duration connectTimeout, @NotNull Duration readTimeout) {

        public Endpoint {
            if (baseUrl == null || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                    || connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()
                    || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
                throw new IllegalArgumentException("HTTP clients require an HTTP URL and positive timeouts");
            }
        }
    }
}
