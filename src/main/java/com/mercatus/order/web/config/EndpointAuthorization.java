package com.mercatus.order.web.config;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.stereotype.Component;

@Component
public class EndpointAuthorization {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String OPERATOR = "OPERATOR";

    public void configure(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth) {
        auth.requestMatchers(POST, "/orders").hasRole(CUSTOMER)
                .requestMatchers(GET, "/orders/*").hasAnyRole(CUSTOMER, OPERATOR)
                .requestMatchers(POST, "/orders/*/cancel").hasRole(CUSTOMER);
    }
}
