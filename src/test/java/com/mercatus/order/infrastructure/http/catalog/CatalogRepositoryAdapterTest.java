package com.mercatus.order.infrastructure.http.catalog;

import com.mercatus.order.application.exception.DependencyContractException;
import com.mercatus.order.application.exception.DependencyUnavailableException;
import com.mercatus.order.application.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.RemoteStub.skuBody;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CatalogRepositoryAdapterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    MockRestServiceServer server;
    CatalogRepositoryAdapter adapter;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://catalog/api/v1");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new CatalogRepositoryAdapter(builder.build());
    }

    @Test
    void consumesExtendedLookupAndEncodesExactSkuAsQueryParameter() {
        String code = "A /?+#% ç";
        var body = skuBody(code, "42.50");
        body.put("newField", "allowed");
        server.expect(requestTo("http://catalog/api/v1/skus?code=A%20%2F%3F%2B%23%25%20%C3%A7"))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

        var sku = adapter.getByCode(code);

        assertThat(sku.availableForSale()).isTrue();
        assertThat(sku.snapshot().skuCode()).isEqualTo(code);
        assertThat(sku.snapshot().productName()).isEqualTo("Example Product");
        assertThat(sku.snapshot().variationAttributes()).containsEntry("size", "M");
        assertThat(sku.snapshot().unitPrice().amount()).isEqualTo(new BigDecimal("42.50"));
        server.verify();
    }

    @Test
    void trustsAvailabilityAndAllowsMissingPriceForUnavailableSku() {
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(
                "{\"code\":\"A\",\"availableForSale\":false,\"price\":null}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getByCode("A").availableForSale()).isFalse();
        server.verify();
    }

    @Test
    void permitsZeroAndEmptyVariationMap() {
        var body = skuBody("A", "0.00");
        body.put("variationAttributes", Map.of());
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

        var sku = adapter.getByCode("A");

        assertThat(sku.snapshot().unitPrice().amount()).isZero();
        assertThat(sku.snapshot().variationAttributes()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{", "{\"code\":\"A\"}",
            "{\"code\":\"B\",\"availableForSale\":false}", "{\"code\":\"A\",\"availableForSale\":\"true\"}"})
    void rejectsMalformedIncompleteOrMismatchedLookup(String body) {
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class)
                .hasMessage("Catalog returned an incompatible response");
    }

    @Test
    void rejectsNegativePriceWrongCurrencyAndMissingSnapshotFields() {
        for (String field : java.util.List.of("price", "currency", "productName", "variationAttributes")) {
            setUp();

            var body = skuBody("A", "42.50");
            body.remove(field);
            server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                    .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);
        }

        for (String price : java.util.List.of("-0.01", "1.001", "100000000000000000.00")) {
            setUp();

            server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                    .andRespond(withSuccess(JSON.writeValueAsString(skuBody("A", price)), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);
        }

        setUp();

        var body = skuBody("A", "42.50");
        body.put("currency", "USD");
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);
    }

    @Test
    void separatesMissingResourceFromUnavailableDependencyAndInvalidContract() {
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withResourceNotFound());

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(ResourceNotFoundException.class);

        setUp();

        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyUnavailableException.class);

        setUp();

        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withException(new SocketTimeoutException("private")));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Catalog is unavailable");

        setUp();

        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);
    }

    @Test
    void rejectsIncorrectSnapshotFieldTypesInsteadOfCoercingThem() {
        var body = skuBody("A", "42.50");
        body.put("productName", 42);
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);

        setUp();

        body.put("productName", "Product");
        body.put("variationAttributes", Map.of("size", 42));
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(withSuccess(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyContractException.class);
    }

    @Test
    void socketFailureWhileReadingResponseBodyIsUnavailableRatherThanIncompatible() {
        server.expect(requestTo("http://catalog/api/v1/skus?code=A"))
                .andRespond(request -> {
                    var response = new org.springframework.mock.http.client.MockClientHttpResponse(new java.io.InputStream() {
                        @Override
                        public int read() throws java.io.IOException {
                            throw new SocketTimeoutException("private body timeout");
                        }
                    }, HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

                    return response;
                });

        assertThatThrownBy(() -> adapter.getByCode("A")).isInstanceOf(DependencyUnavailableException.class)
                .hasMessage("Catalog is unavailable");
    }
}
