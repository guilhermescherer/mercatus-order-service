package com.mercatus.order.infrastructure.messaging;

import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase.Outcome;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase.ReservationResultCommand;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservationFailedPayload;
import com.mercatus.order.infrastructure.messaging.payload.InventoryReservedPayload;
import com.mercatus.order.infrastructure.persistence.inbox.InboxStore;
import java.time.Clock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ReservationResultListener {

    private static final Logger log = LoggerFactory.getLogger(ReservationResultListener.class);

    private final ContractReader reader;
    private final InboxStore inbox;
    private final ApplyReservationResultUseCase applyResult;
    private final TransactionTemplate transactions;
    private final Clock clock;

    ReservationResultListener(ContractReader reader, InboxStore inbox, ApplyReservationResultUseCase applyResult,
            TransactionTemplate transactions, Clock clock) {
        this.reader = reader;
        this.inbox = inbox;
        this.applyResult = applyResult;
        this.transactions = transactions;
        this.clock = clock;
    }

    @KafkaListener(topics = "${order.messaging.topics.events}")
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        MessageEnvelope envelope = reader.readEnvelope(record.value());

        MDC.put("correlationId", envelope.correlationId());
        try {
            ReservationResultCommand command = toCommand(envelope);

            if (command == null) {
                log.warn("Ignoring message {} of unexpected type {}", envelope.messageId(), envelope.type());
                ack.acknowledge();
                return;
            }

            Outcome outcome = transactions.execute(status ->
                    inbox.register(envelope.messageId(), envelope.type(), clock.instant())
                            ? applyResult.apply(command)
                            : null);

            if (outcome == null) {
                log.info("Duplicate message {} ignored", envelope.messageId());
            } else {
                log.info("Order {} reservation result {} -> {}", command.orderId(), command.result(), outcome);
            }

            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private ReservationResultCommand toCommand(MessageEnvelope envelope) {
        return switch (envelope.type()) {
            case InventoryReservedPayload.TYPE -> {
                var payload = reader.readReserved(envelope.payload());
                yield ReservationResultCommand.reserved(payload.orderId(), payload.reservationId(),
                        payload.expiresAt());
            }
            case InventoryReservationFailedPayload.TYPE -> {
                var payload = reader.readReservationFailed(envelope.payload());
                if (!InventoryReservationFailedPayload.OUT_OF_STOCK.equals(payload.reason())) {
                    throw new ContractViolationException("Unknown reservation failure reason " + payload.reason());
                }
                yield ReservationResultCommand.outOfStock(payload.orderId());
            }
            default -> null;
        };
    }
}
