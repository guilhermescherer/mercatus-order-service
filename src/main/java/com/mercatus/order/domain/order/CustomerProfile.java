package com.mercatus.order.domain.order;

import java.util.List;

public record CustomerProfile(CustomerSnapshot customer, boolean active, List<ShippingAddressSnapshot> addresses) {

    public CustomerProfile {
        addresses = List.copyOf(addresses);
    }
}
