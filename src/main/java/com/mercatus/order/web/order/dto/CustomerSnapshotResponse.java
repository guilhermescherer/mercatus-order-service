package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.order.CustomerSnapshot;
import java.util.UUID;

public record CustomerSnapshotResponse(UUID id, String name, String email) {

    static CustomerSnapshotResponse from(CustomerSnapshot customer) {
        return new CustomerSnapshotResponse(customer.id(), customer.name(), customer.email());
    }
}
