package com.mercatus.order.web.order;

import com.mercatus.order.application.exception.DependencyContractException;
import com.mercatus.order.application.exception.DependencyUnavailableException;
import com.mercatus.order.application.exception.ResourceNotFoundException;
import com.mercatus.order.application.exception.StateConflictException;
import com.mercatus.order.application.order.port.in.CreateOrderUseCase;
import com.mercatus.order.application.order.port.in.GetOrderUseCase;
import com.mercatus.order.web.config.CorrelationIdFilter;
import com.mercatus.order.web.error.ApiExceptionHandler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class OrderControllerTest {

    private static final JsonMapper JSON = strictMapper();
    private final CreateOrderUseCase create = mock(CreateOrderUseCase.class);
    private final GetOrderUseCase getOrder = mock(GetOrderUseCase.class);
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(create, getOrder))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(JSON))
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void createdAndReplayResponsesContainContextPathAndNeverExposeKeyOrSensitiveLogs(CapturedOutput output) throws Exception {
        var order = order();
        when(create.create(command("secret-idempotency-key")))
                .thenReturn(new CreateOrderUseCase.Result(order, true),
                        new CreateOrderUseCase.Result(order, false));

        mvc.perform(
                post("/api/v1/orders")
                        .contextPath("/api/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "secret-idempotency-key")
                        .header("X-Correlation-Id", "correlation-test")
                        .content(JSON.writeValueAsString(request()))
        )
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + order.id()))
                .andExpect(header().string("X-Correlation-Id", "correlation-test"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.history[0].actor").value("HTTP_REQUEST"))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.creationRequest").doesNotExist());

        mvc.perform(
                post("/api/v1/orders")
                        .contextPath("/api/v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "secret-idempotency-key")
                        .content(JSON.writeValueAsString(request()))
        )
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/orders/" + order.id()));

        assertThat(output.getAll()).doesNotContain("secret-idempotency-key", "customer@example.com", "Example Street");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidInputIs400WithoutCallingUseCase(String body) throws Exception {
        mvc.perform(
                post("/orders")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        )
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verifyNoInteractions(create, getOrder);
    }

    static Stream<String> invalidBodies() {
        String base = JSON.writeValueAsString(request());

        return Stream.of("{", "null", "{}", base.replace("\"PIX\"", "\"CASH\""),
                base.replace("\"PIX\"", "0"),
                base.replace("\"quantity\":2", "\"quantity\":0"), base.replace("\"quantity\":2", "\"quantity\":-1"),
                base.replace("\"quantity\":2", "\"quantity\":1.5"), base.replace("\"quantity\":2", "\"quantity\":\"2\""),
                base.replace("\"quantity\":2", "\"quantity\":null"), base.replace("\"quantity\":2", "\"quantity\":true"),
                base.replace("\"quantity\":2", "\"quantity\":9223372036854775808"),
                base.replace("\"skuCode\":\"A\"", "\"skuCode\":42"),
                base.replace("\"skuCode\":\"A\"", "\"price\":42,\"skuCode\":\"A\""),
                base.replace(CUSTOMER.toString(), "bad-uuid"),
                base.replace("\"paymentMethod\":\"PIX\",", ""),
                "{\"total\":0," + base.substring(1),
                JSON.writeValueAsString(Map.of("customerId", CUSTOMER, "addressId", ADDRESS, "paymentMethod", "PIX", "items", List.of())),
                "{\"customerId\":\"" + CUSTOMER + "\",\"addressId\":\"" + ADDRESS + "\",\"paymentMethod\":\"PIX\",\"items\":[null]}");
    }

    @Test
    void missingHeaderIs400AndRepeatedSkuIs422() throws Exception {
        mvc.perform(
                post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(request()))
        )
                .andExpect(status().isBadRequest());
        var duplicate = Map.of("customerId", CUSTOMER, "addressId", ADDRESS, "paymentMethod", "PIX", "items",
                List.of(Map.of("skuCode", "A", "quantity", 1), Map.of("skuCode", "A", "quantity", 2)));

        mvc.perform(
                post("/orders")
                        .header("Idempotency-Key", "key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(duplicate))
        )
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(create, getOrder);
    }

    @Test
    void getUsesOnlyLocalUseCaseAndMalformedIdIs400() throws Exception {
        var order = order();
        when(getOrder.get(order.id())).thenReturn(order);

        mvc.perform(
                get("/orders/" + order.id())
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(order.number()));

        mvc.perform(
                get("/orders/not-a-uuid")
        )
                .andExpect(status().isBadRequest());

        mvc.perform(
                get("/orders/1-1-1-1-1")
        )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(create);
    }

    @Test
    void dependencyAndConflictErrorsAreProblemDetailsWithSafeMessages() throws Exception {
        List<RuntimeException> exceptions = List.of(new ResourceNotFoundException("Resource not found"),
                new StateConflictException("Conflicting request"), new DependencyContractException("Incompatible response"),
                new DependencyUnavailableException("Dependency unavailable"));
        int[] statuses = {404, 409, 502, 503};

        for (int i = 0; i < exceptions.size(); i++) {
            reset(create);
            when(create.create(command("key"))).thenThrow(exceptions.get(i));

            mvc.perform(
                    post("/orders")
                            .header("Idempotency-Key", "key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsString(request()))
            )
                    .andExpect(status().is(statuses[i]))
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(statuses[i]));
        }
    }

    private static JsonMapper strictMapper() {
        try (var context = new AnnotationConfigApplicationContext("com.mercatus.order.web.config")) {
            var builder = JsonMapper.builder();
            context.getBean(JsonMapperBuilderCustomizer.class).customize(builder);

            return builder.build();
        }
    }
}
