package com.mercatus.order.application.order;

import com.mercatus.order.application.exception.BusinessRuleException;
import com.mercatus.order.application.exception.StateConflictException;
import com.mercatus.order.application.order.port.out.CatalogRepository;
import com.mercatus.order.application.order.port.out.CustomerRepository;
import com.mercatus.order.application.order.port.out.OrderRepository;
import com.mercatus.order.application.order.usecase.CreateOrderService;
import com.mercatus.order.domain.order.CatalogSku;
import com.mercatus.order.domain.order.CustomerProfile;
import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.PaymentMethod;
import com.mercatus.order.domain.order.SkuSnapshot;
import com.mercatus.order.domain.shared.Money;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CreateOrderServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final CatalogRepository catalog = mock(CatalogRepository.class);
    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final CreateOrderService service = new CreateOrderService(orders, catalog, customers,
            Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID);

    @Test
    void replayDoesNotConsultRemoteServicesOrWriteAnything() {
        when(orders.findByKey("key")).thenReturn(Optional.of(order()));

        var result = service.create(command("key", request()));

        assertThat(result.created()).isFalse();
        assertThat(result.order().total().amount()).isEqualByComparingTo("115.00");

        verifyNoInteractions(catalog, customers);
        verify(orders, never()).tryCreate(any());
        verify(orders, never()).nextNumber();
    }

    @Test
    void conflictingReplayDoesNotConsultRemoteServices() {
        when(orders.findByKey("key")).thenReturn(Optional.of(order()));
        var changed = new OrderCreationRequest(UUID.randomUUID(), ADDRESS, PaymentMethod.PIX, request().items());

        assertThatThrownBy(() -> service.create(command("key", changed))).isInstanceOf(StateConflictException.class);

        verifyNoInteractions(catalog, customers);
    }

    @Test
    void rejectsInactiveCustomerWithoutConsultingCatalog() {
        when(orders.findByKey("key")).thenReturn(Optional.empty());
        when(customers.getById(CUSTOMER))
                .thenReturn(new CustomerProfile(customer(), false, List.of(address())));

        assertThatThrownBy(() -> service.create(command("key", request()))).isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(catalog);
        verify(orders, never()).tryCreate(any());
    }

    @Test
    void rejectsForeignAddress() {
        when(orders.findByKey("key")).thenReturn(Optional.empty());
        when(customers.getById(CUSTOMER)).thenReturn(new CustomerProfile(customer(), true, List.of()));

        assertThatThrownBy(() -> service.create(command("key", request()))).isInstanceOf(BusinessRuleException.class);

        verifyNoInteractions(catalog);
    }

    @Test
    void usesCatalogAvailabilityWithoutRecreatingCatalogRules() {
        when(orders.findByKey("key")).thenReturn(Optional.empty());
        when(customers.getById(CUSTOMER))
                .thenReturn(new CustomerProfile(customer(), true, List.of(address())));
        when(catalog.getByCode("A")).thenReturn(new CatalogSku(false, null));

        assertThatThrownBy(() -> service.create(command("key", request()))).isInstanceOf(BusinessRuleException.class);

        verify(orders, never()).tryCreate(any());
    }

    @Test
    void losingInsertLoadsCommittedWinnerAndReturnsReplay() {
        when(orders.findByKey("key")).thenReturn(Optional.empty(), Optional.of(order()));
        when(customers.getById(CUSTOMER))
                .thenReturn(new CustomerProfile(customer(), true, List.of(address())));
        when(catalog.getByCode("A"))
                .thenReturn(new CatalogSku(true, new SkuSnapshot("A", "Product", Map.of(), Money.zero())));
        when(catalog.getByCode("B"))
                .thenReturn(new CatalogSku(true, new SkuSnapshot("B", "Product", Map.of(), Money.zero())));
        when(orders.nextNumber()).thenReturn("ORD-000000000002");
        when(orders.tryCreate(any())).thenReturn(false);

        var result = service.create(command("key", request()));

        assertThat(result.created()).isFalse();
        assertThat(result.order().total().amount()).isEqualByComparingTo("115.00");

        verify(orders, times(2)).findByKey("key");
        verify(orders, times(1)).tryCreate(any());
    }
}
