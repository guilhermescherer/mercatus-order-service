package com.mercatus.order.application.saga.usecase;

import com.mercatus.order.application.saga.port.in.RetryOverdueSagasUseCase;
import com.mercatus.order.application.saga.port.in.RetrySagaStepUseCase;
import com.mercatus.order.application.saga.port.in.RetrySagaStepUseCase.Outcome;
import com.mercatus.order.application.saga.port.out.OrderSagaRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetryOverdueSagasService implements RetryOverdueSagasUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetryOverdueSagasService.class);

    private final OrderSagaRepository sagas;
    private final RetrySagaStepUseCase retryStep;
    private final Clock clock;
    private final int batchSize;

    public RetryOverdueSagasService(OrderSagaRepository sagas, RetrySagaStepUseCase retryStep, Clock clock,
            @Value("${order.saga.recovery.batch-size:100}") int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("Recovery batch size must be positive");
        }
        this.sagas = sagas;
        this.retryStep = retryStep;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public synchronized int retryOverdue() {
        int handled = 0;

        for (UUID orderId : sagas.findOverdue(clock.instant(), batchSize)) {
            try {
                Outcome outcome = retryStep.retry(orderId);
                report(orderId, outcome);
                if (outcome != Outcome.NOT_OVERDUE) {
                    handled++;
                }
            } catch (RuntimeException ex) {
                log.error("Failed to recover saga of order {}", orderId, ex);
            }
        }

        return handled;
    }

    private void report(UUID orderId, Outcome outcome) {
        switch (outcome) {
            case RESENT -> log.warn("Saga of order {} had no reply before its deadline; command resent", orderId);
            case DEADLINE_EXTENDED ->
                    log.info("Saga of order {} still has an unpublished command; deadline extended", orderId);
            case STALLED -> log.error("Saga of order {} stalled after exhausting retries; operator action needed",
                    orderId);
            case NOT_OVERDUE -> {
            }
        }
    }
}
