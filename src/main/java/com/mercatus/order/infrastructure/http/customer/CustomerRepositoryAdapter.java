package com.mercatus.order.infrastructure.http.customer;

import com.mercatus.order.application.order.port.out.CustomerRepository;
import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.order.CustomerProfile;
import com.mercatus.order.domain.order.CustomerSnapshot;
import com.mercatus.order.domain.order.ShippingAddressSnapshot;
import com.mercatus.order.infrastructure.http.RemoteResponseReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.UUID;

@Repository
class CustomerRepositoryAdapter implements CustomerRepository {

    private static final String CUSTOMER = "Customer";
    private final RestClient client;

    CustomerRepositoryAdapter(@Qualifier("customerClient") RestClient client) {
        this.client = client;
    }

    @Override
    public CustomerProfile getById(UUID id) {
        RestClient.RequestHeadersSpec<?> requestSpec = client.get()
                .uri(builder -> builder.pathSegment("customers", "{id}")
                .build(id));

        var response = RemoteResponseReader.get(requestSpec, CustomerResponse.class, CUSTOMER);

        if (!id.equals(response.id()) || response.status() == null) {
            throw RemoteResponseReader.invalid(CUSTOMER);
        }

        try {
            var customer = new CustomerSnapshot(response.id(), response.name(), response.email());

            var addresses = response.addresses()
                    .stream()
                    .map(a -> new ShippingAddressSnapshot(a.id(), a.zipCode(), a.street(), a.number(), a.complement(), a.district(), a.city(), a.state(), a.country()))
                    .toList();

            if (new HashSet<>(addresses.stream().map(ShippingAddressSnapshot::id).toList()).size() != addresses.size()) {
                throw RemoteResponseReader.invalid(CUSTOMER);
            }

            return new CustomerProfile(customer, response.status() == CustomerResponse.Status.ACTIVE, addresses);
        } catch (DomainRuleException | NullPointerException ex) {
            throw RemoteResponseReader.invalid(CUSTOMER);
        }
    }
}
