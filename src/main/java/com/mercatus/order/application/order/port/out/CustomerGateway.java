package com.mercatus.order.application.order.port.out;

import com.mercatus.order.domain.order.snapshot.CustomerProfile;
import java.util.UUID;

public interface CustomerGateway {

    CustomerProfile getById(UUID id);
}
