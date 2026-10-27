package com.mercatus.order.infrastructure.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
class HttpClientConfig {

    @Bean
    RestClient catalogClient(HttpClientProperties properties) {
        return client(properties.catalog());
    }

    @Bean
    RestClient customerClient(HttpClientProperties properties) {
        return client(properties.customer());
    }

    private RestClient client(HttpClientProperties.Endpoint endpoint) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(endpoint.connectTimeout());
        factory.setReadTimeout(endpoint.readTimeout());

        return RestClient.builder()
                .baseUrl(endpoint.baseUrl().toString())
                .requestFactory(factory)
                .requestInterceptor((request, body, execution) -> {
                    String correlation = MDC.get("correlationId");

                    if (correlation != null) {
                        request.getHeaders().set("X-Correlation-Id", correlation);
                    }

                    return execution.execute(request, body);
                })
                .build();
    }
}
