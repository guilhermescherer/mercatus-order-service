package com.mercatus.order.application.order.port.out;

import com.mercatus.order.domain.order.CustomerProfile;
import java.util.UUID;

public interface CustomerRepository {

    CustomerProfile getById(UUID id);
}
