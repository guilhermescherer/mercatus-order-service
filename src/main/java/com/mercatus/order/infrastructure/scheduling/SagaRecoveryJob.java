package com.mercatus.order.infrastructure.scheduling;

import com.mercatus.order.application.saga.port.in.RetryOverdueSagasUseCase;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class SagaRecoveryJob {

    private final RetryOverdueSagasUseCase retryOverdue;

    SagaRecoveryJob(RetryOverdueSagasUseCase retryOverdue) {
        this.retryOverdue = retryOverdue;
    }

    @Scheduled(fixedDelayString = "${order.saga.recovery.interval-ms:30000}",
            initialDelayString = "${order.saga.recovery.initial-delay-ms:10000}")
    void run() {
        retryOverdue.retryOverdue();
    }
}
