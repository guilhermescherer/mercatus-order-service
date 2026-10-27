package com.mercatus.order.domain.shared;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount, String currency) {

    private static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    public Money {
        if (amount == null || !"BRL".equals(currency)) {
            throw new DomainRuleException("Money must have an amount in BRL");
        }

        try {
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new DomainRuleException("Money must have at most two decimal places");
        }

        if (amount.signum() < 0 || amount.compareTo(MAX) > 0) {
            throw new DomainRuleException("Money exceeds the supported range");
        }
    }

    public static Money zero() {
        return new Money(BigDecimal.ZERO, "BRL");
    }

    public Money multiply(long quantity) {
        if (quantity <= 0) {
            throw new DomainRuleException("Quantity must be positive");
        }

        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public Money add(Money other) {
        return new Money(amount.add(other.amount), currency);
    }
}
