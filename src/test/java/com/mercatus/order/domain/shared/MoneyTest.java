package com.mercatus.order.domain.shared;

import com.mercatus.order.domain.exception.DomainRuleException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class MoneyTest {

    @Test
    void acceptsZeroAndMaximumWithScaleTwo() {
        assertThat(Money.zero().amount().scale()).isEqualTo(2);
        assertThat(new Money(new BigDecimal("99999999999999999.99"), "BRL").amount()).hasScaleOf(2);
        assertThat(new Money(new BigDecimal("42.5"), "BRL").amount()).isEqualTo(new BigDecimal("42.50"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "100000000000000000.00", "1.001"})
    void rejectsInvalidAmount(String amount) {
        assertThatThrownBy(() -> new Money(new BigDecimal(amount), "BRL")).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void rejectsMissingAmountOrNonBrlCurrency() {
        assertThatThrownBy(() -> new Money(null, "BRL")).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> new Money(BigDecimal.ONE, "USD")).isInstanceOf(DomainRuleException.class);
    }

    @Test
    void rejectsDerivedSubtotalAndTotalOverflowBeforePersistence() {
        var max = new Money(new BigDecimal("99999999999999999.99"), "BRL");

        assertThatThrownBy(() -> max.multiply(2)).isInstanceOf(DomainRuleException.class);
        assertThatThrownBy(() -> max.add(new Money(new BigDecimal("0.01"), "BRL")))
                .isInstanceOf(DomainRuleException.class);
        assertThat(Money.zero().multiply(Long.MAX_VALUE)).isEqualTo(Money.zero());
    }
}
