package com.mercatus.order.integration.order;

import com.mercatus.order.domain.order.OrderCreationRequest;
import com.mercatus.order.domain.order.PaymentMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static com.mercatus.order.support.OrderFixture.*;
import static org.assertj.core.api.Assertions.*;

class OrderPersistenceIT extends OrderApiTestSupport {


    @Test
    void rollbackAfterRootAndItemsDoesNotConsumeKey() {
        jdbc.execute("ALTER TABLE order_transitions ADD CONSTRAINT reject_initial_transition CHECK (actor <> 'HTTP_REQUEST')");

        try {
            problem(post("key", request()), 409);

            assertCounts(0, 0, 0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE idempotency_key = 'key'", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE order_transitions DROP CONSTRAINT reject_initial_transition");
        }

        assertThat(post("key", request()).status()).isEqualTo(201);
        assertCounts(1, 2, 1);
    }

    @Test
    void concurrentIdenticalRequestsProduceOneCreatedResponseAndOneHistory() throws Exception {
        List<Reply> responses = race(false);

        assertThat(responses.stream().filter(reply -> reply.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(reply -> reply.status() == 200)).hasSize(7);
        assertThat(responses.stream().map(reply -> reply.order().id()).distinct()).hasSize(1);
        assertCounts(1, 2, 1);
    }

    @Test
    void concurrentDifferentContentHasExactlyOneWinningContent() throws Exception {
        List<Reply> responses = race(true);

        assertThat(responses.stream().filter(reply -> reply.status() == 201)).hasSize(1);
        assertThat(responses.stream().filter(reply -> reply.status() == 200)).hasSize(3);
        assertThat(responses.stream().filter(reply -> reply.status() == 409)).hasSize(4);
        assertCounts(1, 2, 1);
    }

    private List<Reply> race(boolean differentContent) throws Exception {
        remote.catalogBarrier = new CyclicBarrier(8);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<Reply>>();

            for (int i = 0; i < 8; i++) {
                var purchase = new OrderCreationRequest(CUSTOMER, ADDRESS,
                        differentContent && i % 2 == 0 ? PaymentMethod.CARD : PaymentMethod.PIX, request().items());
                futures.add(executor.submit(() -> {
                    start.await();

                    return post("race", purchase);
                }));
            }

            start.countDown();
            var replies = new ArrayList<Reply>();

            for (Future<Reply> future : futures) {
                replies.add(future.get(20, TimeUnit.SECONDS));
            }

            return replies;
        } finally {
            remote.catalogBarrier = null;
        }
    }

    @Test
    void migrationAndDatabaseConstraintsProtectStoredPurchase() {
        post("key", request());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Long.class))
                .isEqualTo(1);

        for (String statement : List.of(
                "UPDATE order_items SET quantity = 0",
                "UPDATE order_items SET unit_price = -1",
                "UPDATE order_items SET subtotal = -1",
                "UPDATE order_items SET currency = 'USD'",
                "UPDATE orders SET total = -1",
                "UPDATE orders SET currency = 'USD'",
                "UPDATE orders SET idempotency_key = ''",
                "INSERT INTO order_items (order_id, position, sku_code, product_name, variation_attributes, quantity, unit_price, subtotal, currency) SELECT order_id, 99, sku_code, product_name, variation_attributes, quantity, unit_price, subtotal, currency FROM order_items LIMIT 1")) {
            assertThatThrownBy(() -> jdbc.execute(statement))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }

        assertCounts(1, 2, 1);
    }
}
