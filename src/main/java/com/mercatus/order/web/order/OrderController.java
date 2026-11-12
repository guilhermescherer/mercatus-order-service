package com.mercatus.order.web.order;

import com.mercatus.order.application.order.port.in.CancelOrderUseCase;
import com.mercatus.order.application.order.port.in.CreateOrderUseCase;
import com.mercatus.order.application.order.port.in.GetOrderUseCase;
import com.mercatus.order.web.order.dto.CreateOrderRequest;
import com.mercatus.order.web.order.dto.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.mercatus.order.web.config.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@ApiResponse(responseCode = "400", description = "Invalid input",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Resource not found",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);
    private final CreateOrderUseCase createOrder;
    private final GetOrderUseCase getOrder;
    private final CancelOrderUseCase cancelOrder;

    OrderController(CreateOrderUseCase createOrder, GetOrderUseCase getOrder, CancelOrderUseCase cancelOrder) {
        this.createOrder = createOrder;
        this.getOrder = getOrder;
        this.cancelOrder = cancelOrder;
    }

    @PostMapping
    @Operation(summary = "Create an order or replay the original purchase",
            description = "Prices and immutable snapshots come from Catalog and Customer. Item order is irrelevant to idempotency.",
            parameters = @Parameter(
                    name = "X-Correlation-Id",
                    in = io.swagger.v3.oas.annotations.enums.ParameterIn.HEADER,
                    description = "Optional request correlation, generated when absent"))
    @ApiResponse(responseCode = "201", description = "Order created",
            headers = @Header(
                    name = "Location", description = "Order URI including the context path",
                    schema = @Schema(type = "string")
            ),
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "200", description = "Existing order replayed",
            headers = @Header(
                    name = "Location", description = "Original order URI including the context path",
                    schema = @Schema(type = "string")
            ),
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "409", description = "Key already used with different content",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Business rule violation",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Incompatible dependency response",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "Dependency unavailable",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrderResponse> create(
            @Parameter(description = "Global, case-sensitive opaque key; 1 to 128 characters without surrounding whitespace")
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateOrderRequest request,
            HttpServletRequest http
    ) {
        var result = createOrder.create(request.toCommand(key, MDC.get(CorrelationIdFilter.MDC_KEY)));

        log.info("Order {} {}", result.order().id(), result.created() ? "created" : "replayed");

        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create(http.getContextPath() + "/orders/" + result.order().id()))
                .body(OrderResponse.from(result.order()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a persisted order with its original snapshots and history")
    OrderResponse get(@Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        return OrderResponse.from(getOrder.get(parseId(id)));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Request the cancellation of an order",
            description = "Idempotent. The order stays CANCELLING until its stock reservation is released or expired.")
    @ApiResponse(responseCode = "200", description = "Order cancelled (now or before)",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "202", description = "Cancellation in progress",
            content = @Content(schema = @Schema(implementation = OrderResponse.class)))
    @ApiResponse(responseCode = "409", description = "Order changed concurrently; retrying is safe",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<OrderResponse> cancel(
            @Parameter(schema = @Schema(type = "string", format = "uuid")) @PathVariable String id) {
        var result = cancelOrder.cancel(parseId(id));

        log.info("Order {} cancellation {}", result.order().id(), result.completed() ? "completed" : "in progress");

        return ResponseEntity.status(result.completed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(OrderResponse.from(result.order()));
    }

    private UUID parseId(String id) {
        UUID parsed = UUID.fromString(id);

        if (!parsed.toString().equalsIgnoreCase(id)) {
            throw new IllegalArgumentException("Invalid order identifier");
        }

        return parsed;
    }
}
