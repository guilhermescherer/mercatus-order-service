package com.mercatus.order.application.order.port.out;

import com.mercatus.order.domain.order.CatalogSku;

public interface CatalogRepository {

    CatalogSku getByCode(String code);
}
