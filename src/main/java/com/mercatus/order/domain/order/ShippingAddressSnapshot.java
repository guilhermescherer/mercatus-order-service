package com.mercatus.order.domain.order;

import java.util.Objects;
import java.util.UUID;

public record ShippingAddressSnapshot(UUID id, String zipCode, String street, String number,
        String complement, String district, String city, String state, String country) {

    public ShippingAddressSnapshot {
        Objects.requireNonNull(id);
        SnapshotValidation.requireText(zipCode, street, number, district, city, state, country);
    }
}
