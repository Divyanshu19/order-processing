package com.order.processing.product.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer error handling configuration with retry and dead-letter topic support.
 *
 * <p>This configuration implements a production-grade error handling pattern using
 * {@link DefaultErrorHandler} with fixed backoff and {@link DeadLetterPublishingRecoverer}:
 *
 * <ol>
 *   <li><strong>Initial Processing</strong> — Message is consumed from the main topic.</li>
 *   <li><strong>Retry with Backoff</strong> — If processing throws an exception, the consumer
 *       pauses briefly and retries the same message (in-place retry with fixed backoff).</li>
 *   <li><strong>Dead Letter Topic</strong> — After exhausting all retries, the message is
 *       published to a DLT for manual investigation.</li>
 * </ol>
 *
 * <p><strong>Advantages over Retry Topic approach:</strong>
 * <ul>
 *   <li><strong>Simpler:</strong> No intermediate retry topics created, only a single DLT</li>
 *   <li><strong>Direct:</strong> Error handler is explicit and configurable at the bean level</li>
 *   <li><strong>Performant:</strong> In-place retries don't create new topic messages initially</li>
 *   <li><strong>Flexible:</strong> Can selectively retry specific exception types</li>
 *   <li><strong>Standard:</strong> This is the recommended Spring Kafka production pattern</li>
 * </ul>
 *
 * <p><strong>Configuration parameters:</strong>
 * <ul>
 *   <li>{@code kafka.error-handler.enabled} — Enable/disable error handler (default: true)</li>
 *   <li>{@code kafka.error-handler.max-attempts} — Total attempts (default: 3)</li>
 *   <li>{@code kafka.error-handler.backoff-delay-ms} — Fixed delay between retries (default: 1000ms)</li>
 * </ul>
 *
 * <p><strong>Retry Behavior Example (FixedBackOff):</strong>
 * <pre>
 * Initial attempt:           T=0
 * Retry 1 (after delay):     T=1000ms
 * Retry 2 (after delay):     T=2000ms
 * DLT (if still fail):       After last retry
 * </pre>
 *
 * @see DefaultErrorHandler
 * @see DeadLetterPublishingRecoverer
 */
@Slf4j
@Configuration
public class KafkaErrorHandlerConfig {

    @Value("${kafka.error-handler.enabled:true}")
    private boolean errorHandlerEnabled;

    @Value("${kafka.error-handler.max-attempts:3}")
    private int maxAttempts;

    @Value("${kafka.error-handler.backoff-delay-ms:1000}")
    private long backoffDelayMs;

    /**
     * Creates a {@link DefaultErrorHandler} bean that applies to all {@code @KafkaListener}
     * methods in the container factory.
     *
     * <p>The handler implements the following recovery strategy:
     * <ol>
     *   <li>On exception, log with full context (topic, partition, offset, headers)</li>
     *   <li>Wait for {@code backoffDelayMs}</li>
     *   <li>Retry the message (up to {@code maxAttempts} total)</li>
     *   <li>If all retries exhausted, send to DLT topic</li>
     * </ol>
     *
     * <p><strong>DLT Topic Naming:</strong> Original topic name + {@code "-dlt"} suffix
     * <pre>
     * order-placed           → order-placed-dlt
     * </pre>
     *
     * @param kafkaTemplate the template used to publish messages to DLT
     * @return a configured {@link DefaultErrorHandler}
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        if (!errorHandlerEnabled) {
            log.info("Kafka error handler is DISABLED");
            return null;
        }

        log.info("Configuring DefaultErrorHandler with maxAttempts={}, backoffDelay={}ms",
                maxAttempts, backoffDelayMs);

        // Create a fixed backoff strategy: wait 'backoffDelayMs' between each retry
        FixedBackOff fixedBackOff = new FixedBackOff(backoffDelayMs, maxAttempts - 1);

        // Create a dead letter publisher that sends failed messages to a DLT topic
        // Topic name is derived from original topic + "-dlt" suffix
        DeadLetterPublishingRecoverer deadLetterRecoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> {
                    // Modify the topic name: append "-dlt" suffix
                    String dlqTopic = record.topic() + "-dlt";
                    log.error("Sending message to DLT after exhausting retries: " +
                                    "originalTopic={}, dlqTopic={}, partition={}, offset={}, " +
                                    "key={}, exception={}",
                            record.topic(), dlqTopic, record.partition(), record.offset(),
                            record.key(), ex.getMessage(), ex);
                    return new TopicPartition(dlqTopic, record.partition());
                });

        // Create the error handler with fixed backoff and DLT recovery
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterRecoverer, fixedBackOff);

        // DeserializationException cannot be retried — the bytes are already corrupted or the
        // wrong type. Skip retries entirely and route straight to the DLT so the consumer
        // is not stuck in an infinite error loop replaying the same unreadable message.
        errorHandler.addNotRetryableExceptions(DeserializationException.class);

        return errorHandler;
    }


}
