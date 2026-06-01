package com.order.processing.payment.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes payment-related events to Kafka.
 *
 * <p>Wraps {@link KafkaTemplate} so that Kafka details (topic names, key
 * strategy, callback logging) are isolated from the listener / service layer.
 *
 * <p>Message key strategy: {@code orderId.toString()} — guarantees all events
 * for the same order land on the same partition and are consumed in order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${kafka.topics.payment-completed}")
    private String paymentCompletedTopic;

    @Value("${kafka.topics.payment-failed}")
    private String paymentFailedTopic;

    /**
     * Publishes a {@link PaymentCompletedEvent} to the {@code payment-completed} topic.
     *
     * <p>The send is asynchronous; the {@link CompletableFuture} is used solely for logging.
     *
     * @param event the fully populated event built after a successful payment
     */
    public void publishPaymentCompleted(PaymentCompletedEvent event) {
        String key = event.getOrderId().toString();
        log.info("Publishing PaymentCompletedEvent to topic={}, key={}, orderId={}, transactionId={}",
                paymentCompletedTopic, key, event.getOrderId(), event.getTransactionId());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(paymentCompletedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish PaymentCompletedEvent for orderId={}: {}",
                        event.getOrderId(), ex.getMessage(), ex);
            } else {
                log.info("PaymentCompletedEvent published successfully for orderId={} — partition={}, offset={}",
                        event.getOrderId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * Publishes a {@link PaymentFailedEvent} to the {@code payment-failed} topic.
     *
     * <p>The send is asynchronous; the {@link CompletableFuture} is used solely for logging.
     *
     * @param event the fully populated failure event including the reason
     */
    public void publishPaymentFailed(PaymentFailedEvent event) {
        String key = event.getOrderId().toString();
        log.info("Publishing PaymentFailedEvent to topic={}, key={}, orderId={}, reason={}",
                paymentFailedTopic, key, event.getOrderId(), event.getReason());

        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(paymentFailedTopic, key, event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish PaymentFailedEvent for orderId={}: {}",
                        event.getOrderId(), ex.getMessage(), ex);
            } else {
                log.info("PaymentFailedEvent published successfully for orderId={} — partition={}, offset={}",
                        event.getOrderId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}
