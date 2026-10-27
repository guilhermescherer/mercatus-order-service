package com.mercatus.order.infrastructure.http;

import com.mercatus.order.application.exception.DependencyContractException;
import com.mercatus.order.application.exception.DependencyUnavailableException;
import com.mercatus.order.application.exception.ResourceNotFoundException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.exc.JacksonIOException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

public final class RemoteResponseReader {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private RemoteResponseReader() {
    }

    public static <T> T get(RestClient.RequestHeadersSpec<?> request, Class<T> type, String dependency) {
        try {
            return request.exchange((req, response) -> {
                int status = response.getStatusCode().value();

                if (status == 404) {
                    throw new ResourceNotFoundException(dependency + " resource not found");
                }

                if (status >= 500 || status == 429 || status == 408) {
                    throw new DependencyUnavailableException(dependency + " is unavailable");
                }

                if (status < 200 || status >= 300) {
                    throw invalid(dependency);
                }

                try {
                    T value = JSON.readValue(response.getBody(), type);

                    if (value == null) {
                        throw invalid(dependency);
                    }

                    return value;
                } catch (JacksonIOException ex) {
                    throw new DependencyUnavailableException(dependency + " is unavailable");
                } catch (tools.jackson.core.JacksonException ex) {
                    throw invalid(dependency);
                }
            });
        } catch (ResourceAccessException ex) {
            throw new DependencyUnavailableException(dependency + " is unavailable");
        } catch (RestClientException ex) {
            throw invalid(dependency);
        }
    }

    public static DependencyContractException invalid(String dependency) {
        return new DependencyContractException(dependency + " returned an incompatible response");
    }
}
