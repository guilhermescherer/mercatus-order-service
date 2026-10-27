package com.mercatus.order.web.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record OrderLineRequest(@NotBlank String skuCode, @NotNull @Positive Long quantity) {
}
