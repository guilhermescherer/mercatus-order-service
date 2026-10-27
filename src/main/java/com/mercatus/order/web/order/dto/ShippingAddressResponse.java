package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.order.ShippingAddressSnapshot;
import java.util.UUID;

public record ShippingAddressResponse(UUID id, String zipCode, String street, String number,
        String complement, String district, String city, String state, String country) {

    static ShippingAddressResponse from(ShippingAddressSnapshot address) {
        return new ShippingAddressResponse(address.id(), address.zipCode(), address.street(), address.number(),
                address.complement(), address.district(), address.city(), address.state(), address.country());
    }
}
