package com.mercatus.order.infrastructure.config;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(HttpClientProperties.class)
class HttpClientConfig {

    @Bean
    RestClient catalogClient(HttpClientProperties properties, ClientHttpRequestInterceptor serviceTokenInterceptor) {
        return client(properties.catalog(), serviceTokenInterceptor);
    }

    @Bean
    RestClient customerClient(HttpClientProperties properties, ClientHttpRequestInterceptor serviceTokenInterceptor) {
        return client(properties.customer(), serviceTokenInterceptor);
    }

    private RestClient client(HttpClientProperties.Endpoint endpoint, ClientHttpRequestInterceptor serviceToken) {
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
                .requestInterceptor(serviceToken)
                .build();
    }
}
