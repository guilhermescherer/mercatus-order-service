package com.mercatus.order.integration.order;

import static com.mercatus.order.support.OrderFixture.CUSTOMER;
import static com.mercatus.order.support.OrderFixture.request;
import static org.assertj.core.api.Assertions.assertThat;

import com.mercatus.order.support.RemoteStub;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

class ClientCredentialsIT extends OrderApiTestSupport {

    private static final String REGISTRATION = "order-service";

    @Autowired
    OAuth2AuthorizedClientService authorizedClients;

    @BeforeEach
    void forgetCachedServiceToken() {
        authorizedClients.removeAuthorizedClient(REGISTRATION, REGISTRATION);
    }

    @Test
    void serviceTokenIsSentToCatalogAndCustomer() {
        assertThat(post("key", request()).status()).isEqualTo(201);

        assertThat(remote.tokenRequests).hasValue(1);
        assertThat(remote.paths).isNotEmpty();
        assertThat(remote.authorizations)
                .hasSameSizeAs(remote.paths)
                .containsOnly("Bearer " + RemoteStub.SERVICE_TOKEN);
    }

    @Test
    void serviceTokenIsReusedWhileValid() {
        assertThat(post("first", request()).status()).isEqualTo(201);
        assertThat(post("second", request()).status()).isEqualTo(201);

        assertThat(remote.tokenRequests).hasValue(1);
    }

    @Test
    void unreachableIdentityProviderIs503AndCreatesNothing() {
        remote.tokenUnreachable();

        problem(post("key", request()), 503);

        assertCounts(0, 0, 0);
        assertThat(remote.paths).isEmpty();

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
    }

    @Test
    void identityProviderServerErrorIs503() {
        remote.tokenReply(500, Map.of());

        problem(post("key", request()), 503);

        assertCounts(0, 0, 0);
    }

    @Test
    void rejectedServiceCredentialsAre502() {
        remote.tokenReply(401, Map.of("error", "invalid_client"));

        problem(post("key", request()), 502);

        assertCounts(0, 0, 0);
        assertThat(remote.paths).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void dependencyRejectingServiceTokenIs502(int status) {
        remote.reply("/customers/" + CUSTOMER, status, Map.of());

        problem(post("key", request()), 502);

        assertCounts(0, 0, 0);
    }

    @Test
    void rejectedServiceTokenIsDiscardedAndRenewed() {
        remote.reply("/customers/" + CUSTOMER, 401, Map.of());
        problem(post("key", request()), 502);

        remote.reset();

        assertThat(post("key", request()).status()).isEqualTo(201);
        assertThat(remote.tokenRequests).hasValue(1);
    }
}
