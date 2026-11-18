package com.mercatus.order.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
class ServiceTokenConfig {

    static final String REGISTRATION_ID = "order-service";
    private static final Authentication SERVICE_PRINCIPAL =
            UsernamePasswordAuthenticationToken.unauthenticated(REGISTRATION_ID, null);

    @Bean
    OAuth2ClientHttpRequestInterceptor serviceTokenInterceptor(ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService authorizedClients, HttpClientProperties properties) {
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(clientCredentials(properties.identityProvider()));

        var interceptor = new OAuth2ClientHttpRequestInterceptor(manager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION_ID);
        interceptor.setPrincipalResolver(request -> SERVICE_PRINCIPAL);
        interceptor.setAuthorizationFailureHandler(
                OAuth2ClientHttpRequestInterceptor.authorizationFailureHandler(authorizedClients));
        return interceptor;
    }

    private static OAuth2AuthorizedClientProvider clientCredentials(HttpClientProperties.Timeouts timeouts) {
        var tokenClient = new RestClientClientCredentialsTokenResponseClient();
        tokenClient.setRestClient(tokenRestClient(timeouts));
        return OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(credentials -> credentials.accessTokenResponseClient(tokenClient))
                .build();
    }

    private static RestClient tokenRestClient(HttpClientProperties.Timeouts timeouts) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeouts.connectTimeout());
        factory.setReadTimeout(timeouts.readTimeout());

        return RestClient.builder()
                .requestFactory(factory)
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }
}
