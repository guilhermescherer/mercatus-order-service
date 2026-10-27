package com.mercatus.order.infrastructure.http.customer;

import java.util.UUID;

record AddressResponse(UUID id, String zipCode, String street, String number, String complement,
        String district, String city, String state, String country) {
}
