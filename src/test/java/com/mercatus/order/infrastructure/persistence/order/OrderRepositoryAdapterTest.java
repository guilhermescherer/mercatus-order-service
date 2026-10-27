package com.mercatus.order.infrastructure.persistence.order;

import com.mercatus.order.domain.order.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static com.mercatus.order.support.OrderFixture.NOW;
import static com.mercatus.order.support.OrderFixture.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderRepositoryAdapterTest {

    private final OrderJpaRepository orders = mock(OrderJpaRepository.class);
    private final OrderItemJpaRepository items = mock(OrderItemJpaRepository.class);
    private final OrderTransitionJpaRepository transitions = mock(OrderTransitionJpaRepository.class);
    private final OrderRepositoryAdapter adapter = new OrderRepositoryAdapter(orders, items, transitions);

    @Test
    void losingCreationDoesNotWriteItemsOrHistory() {
        boolean created = adapter.tryCreate(order());

        assertThat(created).isFalse();

        verifyNoInteractions(items, transitions);
    }

    @Test
    void winningCreationWritesSnapshotsItemsAndInitialHistoryInOrder() {
        Order order = order();
        rootInsertSucceeds();

        boolean created = adapter.tryCreate(order);

        assertThat(created).isTrue();

        var writes = inOrder(orders, items, transitions);

        writes.verify(orders).insertIfAbsent(
                order.id(), order.number(), order.idempotencyKey(),
                OrderMapper.json(order.creationRequest()),
                OrderMapper.json(order.customer()),
                OrderMapper.json(order.shippingAddress()),
                "PIX", "CREATED", new BigDecimal("115.00"), "BRL", NOW, NOW, 0
        );

        writes.verify(items).insert(
                order.id(), 0, "A", "Example Product", "{\"size\":\"M\"}",
                2, new BigDecimal("42.50"), new BigDecimal("85.00"), "BRL"
        );

        writes.verify(items).insert(
                order.id(), 1, "B", "Example Product", "{\"size\":\"M\"}",
                3, new BigDecimal("10.00"), new BigDecimal("30.00"), "BRL"
        );

        writes.verify(transitions).insert(order.id(), 0, null, "CREATED", "ORDER_CREATED", NOW, "HTTP_REQUEST");
        writes.verifyNoMoreInteractions();
    }

    @Test
    void childWriteFailurePropagatesWithoutWritingHistory() {
        Order order = order();
        var failure = new DataIntegrityViolationException("Item write failed");
        rootInsertSucceeds();

        doThrow(failure).when(items).insert(
                order.id(), 0, "A", "Example Product", "{\"size\":\"M\"}",
                2, new BigDecimal("42.50"), new BigDecimal("85.00"), "BRL"
        );

        assertThatThrownBy(() -> adapter.tryCreate(order)).isSameAs(failure);

        verifyNoInteractions(transitions);
    }

    @ParameterizedTest
    @CsvSource({"1, ORD-000000000001", "1234567890123, ORD-1234567890123"})
    void formatsPublicNumberFromRepositorySequence(long sequence, String expected) {
        when(orders.nextNumber()).thenReturn(sequence);

        assertThat(adapter.nextNumber()).isEqualTo(expected);
    }

    private void rootInsertSucceeds() {
        when(orders.insertIfAbsent(
                any(), anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyString(), any(), any(), anyLong()
        )).thenReturn(1);
    }
}
