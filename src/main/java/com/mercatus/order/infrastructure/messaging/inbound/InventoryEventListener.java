package com.mercatus.order.infrastructure.messaging.inbound;

import com.mercatus.order.application.saga.port.in.ApplyReservationClosureUseCase;
import com.mercatus.order.application.saga.port.in.ApplyReservationClosureUseCase.Closure;
import com.mercatus.order.application.saga.port.in.ApplyReservationClosureUseCase.ReservationClosureCommand;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase;
import com.mercatus.order.application.saga.port.in.ApplyReservationResultUseCase.ReservationResultCommand;
import com.mercatus.order.infrastructure.messaging.contract.ContractReader;
import com.mercatus.order.infrastructure.messaging.contract.MessageEnvelope;
import com.mercatus.order.infrastructure.messaging.contract.payload.InventoryReservationFailedPayload;
import com.mercatus.order.infrastructure.messaging.contract.payload.InventoryReservedPayload;
import com.mercatus.order.infrastructure.messaging.contract.payload.ReservationExpiredPayload;
import com.mercatus.order.infrastructure.messaging.contract.payload.ReservationReleasedPayload;
import com.mercatus.order.infrastructure.messaging.inbox.InboxStore;
import java.time.Clock;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final ContractReader reader;
    private final InboxStore inbox;
    private final ApplyReservationResultUseCase applyResult;
    private final ApplyReservationClosureUseCase applyClosure;
    private final TransactionTemplate transactions;
    private final Clock clock;

    InventoryEventListener(ContractReader reader, InboxStore inbox, ApplyReservationResultUseCase applyResult,
            ApplyReservationClosureUseCase applyClosure, TransactionTemplate transactions, Clock clock) {
        this.reader = reader;
        this.inbox = inbox;
        this.applyResult = applyResult;
        this.applyClosure = applyClosure;
        this.transactions = transactions;
        this.clock = clock;
    }

    @KafkaListener(topics = "${order.messaging.topics.events}")
    void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        MessageEnvelope envelope = reader.readEnvelope(record.value());

        MDC.put("correlationId", envelope.correlationId());
        try {
            switch (envelope.type()) {
                case InventoryReservedPayload.TYPE -> {
                    var payload = reader.readReserved(envelope.payload());
                    result(envelope, ReservationResultCommand.reserved(payload.orderId(), payload.reservationId(), payload.expiresAt()));
                }
                case InventoryReservationFailedPayload.TYPE -> {
                    var payload = reader.readReservationFailed(envelope.payload());
                    result(envelope, switch (payload.reason()) {
                        case OUT_OF_STOCK -> ReservationResultCommand.outOfStock(payload.orderId());
                    });
                }
                case ReservationReleasedPayload.TYPE -> {
                    var payload = reader.readReleased(envelope.payload());
                    closure(envelope, new ReservationClosureCommand(payload.orderId(), payload.reservationId(), Closure.RELEASED));
                }
                case ReservationExpiredPayload.TYPE -> {
                    var payload = reader.readExpired(envelope.payload());
                    closure(envelope, new ReservationClosureCommand(payload.orderId(), payload.reservationId(), Closure.EXPIRED));
                }
                default -> log.warn("Ignoring message {} of unexpected type {}", envelope.messageId(),
                        envelope.type());
            }

            ack.acknowledge();
        } finally {
            MDC.remove("correlationId");
        }
    }

    private void result(MessageEnvelope envelope, ReservationResultCommand command) {
        var outcome = once(envelope, () -> applyResult.apply(command));

        if (outcome != null) {
            log.info("Order {} reservation result {} -> {}", command.orderId(), command.result(), outcome);
        }
    }

    private void closure(MessageEnvelope envelope, ReservationClosureCommand command) {
        var outcome = once(envelope, () -> applyClosure.apply(command));

        if (outcome == ApplyReservationClosureUseCase.Outcome.IGNORED_UNKNOWN_RESERVATION
                || outcome == ApplyReservationClosureUseCase.Outcome.IGNORED_UNEXPECTED) {
            log.warn("Order {} reservation {} {} -> {}", command.orderId(), command.reservationId(),
                    command.closure(), outcome);
        } else if (outcome != null) {
            log.info("Order {} reservation {} {} -> {}", command.orderId(), command.reservationId(),
                    command.closure(), outcome);
        }
    }

    private <T> T once(MessageEnvelope envelope, Supplier<T> effect) {
        T outcome = transactions.execute(status ->
                inbox.register(envelope.messageId(), envelope.type(), clock.instant()) ? effect.get() : null);

        if (outcome == null) {
            log.info("Duplicate message {} ignored", envelope.messageId());
        }

        return outcome;
    }
}
