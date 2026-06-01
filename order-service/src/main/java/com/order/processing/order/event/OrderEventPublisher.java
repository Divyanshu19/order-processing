package com.order.processing.order.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes order-related events to Kafka.
 *
 * <p>Wraps {@link KafkaTemplate} so that Kafka details (topic names, key
 * strategy, callback logging) are isolated from the service layer.
 *
 * <p>Message key strategy: {@code orderId.toString()} — guarantees all events
 * for the same order land on the same partition and are consumed in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.order-placed}")
    private String orderPlacedTopic;

    @Value("${kafka.topics.payment-initiated}")
    private String paymentInitiatedTopic;

    /**
     * Publishes a {@link PaymentInitiatedEvent} to the {@code payment-initiated} topic.
     *
     * <p>Called by the saga listener after a {@code product-reserved} event is consumed.
     * The send is asynchronous; the {@link CompletableFuture} is used solely for logging.
     *
     * @param event the fully populated event built from the reserved order
     */
    public void publishPaymentInitiated(PaymentInitiatedEvent event) {
        String key = event.getOrderId().toString();
        log.info("Publishing PaymentInitiatedEvent to topic={}, key={}, orderId={}, amount={}, method={}",
                paymentInitiatedTopic, key, event.getOrderId(), event.getAmount(), event.getPaymentMethod());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(paymentInitiatedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish PaymentInitiatedEvent for orderId={}: {}",
                        event.getOrderId(), ex.getMessage(), ex);
            } else {
                log.info("PaymentInitiatedEvent published successfully for orderId={} — partition={}, offset={}",
                        event.getOrderId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Publishes an {@link OrderPlacedEvent} to the {@code order-placed} topic.
     *
     * <p>The send is asynchronous; the returned {@link CompletableFuture} is
     * used solely for logging — the caller does not block on Kafka acknowledgment.
     * If the broker is unreachable the failure is logged but does not propagate
     * to the HTTP response (the order is already durably saved in the DB).
     *
     * @param event the fully populated event built from the persisted order
     */
    public void publishOrderPlaced(OrderPlacedEvent event) {
        String key = event.getOrderId().toString();
        log.info("Publishing OrderPlacedEvent to topic={}, key={}, orderId={}, productId={}, quantity={}",
                orderPlacedTopic, key, event.getOrderId(), event.getProductId(), event.getQuantity());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(orderPlacedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish OrderPlacedEvent for orderId={}: {}",
                        event.getOrderId(), ex.getMessage(), ex);
            } else {
                log.info("OrderPlacedEvent published successfully for orderId={} — "
                                + "partition={}, offset={}",
                        event.getOrderId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
