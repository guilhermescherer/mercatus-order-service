package com.mercatus.order.application.order.port.out;

import com.mercatus.order.domain.order.snapshot.CatalogSku;

public interface CatalogGateway {

    CatalogSku getByCode(String code);
}
