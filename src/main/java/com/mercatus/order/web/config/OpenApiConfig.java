package com.mercatus.order.web.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI orderOpenApi() {
        return new OpenAPI().info(
                new Info().title("Order Service API")
                        .description("Immutable order snapshots and idempotent creation")
                        .version("v1")
        );
    }
}
