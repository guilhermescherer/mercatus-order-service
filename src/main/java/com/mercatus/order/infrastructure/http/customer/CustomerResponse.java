package com.mercatus.order.infrastructure.http.customer;

import java.util.List;
import java.util.UUID;

record CustomerResponse(UUID id, String name, String email, Status status, List<AddressResponse> addresses) {

    enum Status {

        ACTIVE, INACTIVE
    }
}
