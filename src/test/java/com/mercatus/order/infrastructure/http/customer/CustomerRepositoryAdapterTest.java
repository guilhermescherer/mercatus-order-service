package com.mercatus.order.infrastructure.http.customer;

import com.mercatus.order.application.exception.DependencyContractException;
import com.mercatus.order.application.exception.DependencyUnavailableException;
import com.mercatus.order.application.exception.ResourceNotFoundException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.OrderFixture.*;
import static com.mercatus.order.support.RemoteStub.customerBody;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CustomerRepositoryAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    MockRestServiceServer server;
    CustomerRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://customer/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CustomerRepositoryAdapter(builder.build());
    }

    @Test
    void consumesExistingCustomerContractIncludingFullAddressAndToleratesAdditiveFields() {
        respond(customerBody());

        var profile = adapter.getById(CUSTOMER);

        assertThat(profile.active()).isTrue();
        assertThat(profile.customer()).isEqualTo(customer());
        assertThat(profile.addresses()).containsExactly(address());
        server.verify();
    }

    @Test
    void preservesInactiveStatusForApplicationBusinessValidation() {
        var body = customerBody();
        body.put("status", "INACTIVE");
        respond(body);

        assertThat(adapter.getById(CUSTOMER).active()).isFalse();
    }

    @Test
    void rejectsIncompleteOrMismatchedCustomerAndUnknownStatus() {
        for (String field : List.of("id", "name", "email", "status", "addresses")) {
            setUp();

            var body = customerBody();
            body.remove(field);
            respond(body);

            assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyContractException.class);
        }

        setUp();

        var body = customerBody();
        body.put("id", UUID.randomUUID());
        respond(body);

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyContractException.class);

        setUp();

        body.put("id", CUSTOMER);
        body.put("status", "UNKNOWN");
        respond(body);

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyContractException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1})
    void rejectsNumericStatusInsteadOfInterpretingEnumOrdinal(int status) {
        var body = customerBody();
        body.put("status", status);
        respond(body);

        assertThatThrownBy(() -> adapter.getById(CUSTOMER))
                .isInstanceOf(DependencyContractException.class)
                .hasMessage("Customer returned an incompatible response");
        server.verify();
    }

    @Test
    void rejectsIncompleteOrAmbiguousAddressList() {
        var body = customerBody();
        body.put("addresses", List.of(Map.of("id", ADDRESS)));
        respond(body);

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyContractException.class);

        setUp();

        body.put("addresses", List.of(address(), address()));
        respond(body);

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyContractException.class);
    }

    @Test
    void separatesNotFoundTechnicalFailureAndConnectionFailure() {
        server.expect(requestTo("http://customer/api/v1/customers/" + CUSTOMER))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(ResourceNotFoundException.class);

        setUp();

        server.expect(requestTo("http://customer/api/v1/customers/" + CUSTOMER))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyUnavailableException.class);

        setUp();

        server.expect(requestTo("http://customer/api/v1/customers/" + CUSTOMER))
                .andRespond(withException(new ConnectException("private")));

        assertThatThrownBy(() -> adapter.getById(CUSTOMER)).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Customer is unavailable");
    }

    private void respond(Object body) {
        server.expect(requestTo("http://customer/api/v1/customers/" + CUSTOMER))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));
    }
}
