package com.mercatus.order.infrastructure.http.catalog;

import com.mercatus.order.application.order.port.out.CatalogGateway;
import com.mercatus.order.domain.exception.DomainRuleException;
import com.mercatus.order.domain.order.snapshot.CatalogSku;
import com.mercatus.order.domain.order.snapshot.SkuSnapshot;
import com.mercatus.order.domain.shared.Money;
import com.mercatus.order.infrastructure.http.RemoteResponseReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

@Repository
class CatalogGatewayAdapter implements CatalogGateway {

    private static final String CATALOG = "Catalog";
    private final RestClient client;

    CatalogGatewayAdapter(@Qualifier("catalogClient") RestClient client) {
        this.client = client;
    }

    @Override
    public CatalogSku getByCode(String code) {
        RestClient.RequestHeadersSpec<?> requestSpec = client.get()
                .uri(builder -> builder.pathSegment("skus")
                        .queryParam("code", "{code}")
                        .build(code));

        var response = RemoteResponseReader.get(requestSpec, CatalogSkuResponse.class, CATALOG);

        if (!code.equals(response.code()) || response.availableForSale() == null) {
            throw RemoteResponseReader.invalid(CATALOG);
        }

        if (!response.availableForSale()) {
            return new CatalogSku(false, null);
        }

        try {
            var snapshot = new SkuSnapshot(response.code(), response.productName(), response.variationAttributes(),
                    new Money(response.price(), response.currency()));

            return new CatalogSku(true, snapshot);
        } catch (DomainRuleException | NullPointerException ex) {
            throw RemoteResponseReader.invalid(CATALOG);
        }
    }
}
