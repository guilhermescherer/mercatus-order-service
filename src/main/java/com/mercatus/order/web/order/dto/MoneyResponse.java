package com.mercatus.order.web.order.dto;

import com.mercatus.order.domain.shared.Money;
import java.math.BigDecimal;

public record MoneyResponse(BigDecimal amount, String currency) {

    static MoneyResponse from(Money money) {
        return new MoneyResponse(money.amount(), money.currency());
    }
}
