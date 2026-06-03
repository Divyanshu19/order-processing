package com.order.processing.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Processes messages that have failed and been sent to Dead Letter Topics (DLTs).
 *
 * <p>After a message fails and exhausts all configured retries (via
 * {@link com.order.processing.order.config.KafkaErrorHandlerConfig}), it is
 * automatically sent to a DLT topic. This listener consumes from those DLTs
 * and performs investigation, alerting, and potential manual recovery.
 *
 * <p><strong>DLT Topic Naming Convention:</strong>
 * <pre>
 * Original Topic              →  DLT Topic
 * ─────────────────────────────────────────────────────────
 * payment-completed           →  payment-completed-dlt
 * payment-failed              →  payment-failed-dlt
 * product-reserved            →  product-reserved-dlt
 * product-reservation-failed  →  product-reservation-failed-dlt
 * </pre>
 *
 * <p><strong>Consumer Group:</strong> {@code order-service-dlt-group} (dedicated group
 * to keep DLT processing isolated from main saga event processing)
 *
 * <p><strong>Available Fields in Kafka Headers:</strong>
 * <ul>
 *   <li>{@code kafka_receivedPartition} — Original partition number</li>
 *   <li>{@code kafka_receivedTimestamp} — Message receive timestamp</li>
 *   <li>{@code kafka_exceptionMessage} — Exception message from failed handler</li>
 *   <li>{@code kafka_exceptionStackTrace} — Exception stack trace</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterListener {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Consumes messages that failed on {@code payment-completed} and were sent to
     * the {@code payment-completed-dlt} topic.
     *
     * <p>This would typically handle cases where:
     * <ul>
     *   <li>Order lookup in the database failed repeatedly due to transient errors.</li>
     *   <li>Order status update transaction failed even with exponential retries.</li>
     *   <li>Unexpected deserialization or NPE in the original handler.</li>
     * </ul>
     *
     * <p><strong>Recovery Actions:</strong>
     * <ol>
     *   <li>Log with full context (partition, offset, exception).</li>
     *   <li>Record failure in a monitoring/alerting system.</li>
     *   <li>Send alert to operations (e.g., Slack, PagerDuty).</li>
     *   <li>Store in a failure tracking database for manual review.</li>
     *   <li>Implement exponential backoff for manual retries.</li>
     * </ol>
     *
     * @param message   the message payload (typically JSON as String)
     * @param partition the original Kafka partition
     * @param offset    the message offset in the partition
     * @param exception the exception message from the failed listener
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-completed}-dlt",
            groupId = "order-service-dlt-group"
    )
    public void handlePaymentCompletedDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = "kafka_offset", required = false) Long offset,
            @Header(name = "kafka_exceptionMessage", required = false) String exception) {

        log.error("=== DEAD LETTER: payment-completed-dlt ===\n" +
                        "Partition: {}\n" +
                        "Offset: {}\n" +
                        "Exception: {}\n" +
                        "Message: {}",
                partition, offset, exception, message);

        recordAndAlert("payment-completed-dlt", message, exception);
    }

    /**
     * Consumes messages that failed on {@code payment-failed} and were sent to
     * the {@code payment-failed-dlt} topic.
     *
     * @param message   the message payload
     * @param partition the original partition
     * @param offset    the message offset
     * @param exception the exception message from the failed listener
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-failed}-dlt",
            groupId = "order-service-dlt-group"
    )
    public void handlePaymentFailedDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = "kafka_offset", required = false) Long offset,
            @Header(name = "kafka_exceptionMessage", required = false) String exception) {

        log.error("=== DEAD LETTER: payment-failed-dlt ===\n" +
                        "Partition: {}\n" +
                        "Offset: {}\n" +
                        "Exception: {}\n" +
                        "Message: {}",
                partition, offset, exception, message);

        recordAndAlert("payment-failed-dlt", message, exception);
    }

    /**
     * Consumes messages that failed on {@code product-reserved} and were sent to
     * the {@code product-reserved-dlt} topic.
     *
     * @param message   the message payload
     * @param partition the original partition
     * @param offset    the message offset
     * @param exception the exception message from the failed listener
     */
    @KafkaListener(
            topics = "${kafka.topics.product-reserved}-dlt",
            groupId = "order-service-dlt-group"
    )
    public void handleProductReservedDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = "kafka_offset", required = false) Long offset,
            @Header(name = "kafka_exceptionMessage", required = false) String exception) {

        log.error("=== DEAD LETTER: product-reserved-dlt ===\n" +
                        "Partition: {}\n" +
                        "Offset: {}\n" +
                        "Exception: {}\n" +
                        "Message: {}",
                partition, offset, exception, message);

        recordAndAlert("product-reserved-dlt", message, exception);
    }

    /**
     * Consumes messages that failed on {@code product-reservation-failed} and were sent to
     * the {@code product-reservation-failed-dlt} topic.
     *
     * @param message   the message payload
     * @param partition the original partition
     * @param offset    the message offset
     * @param exception the exception message from the failed listener
     */
    @KafkaListener(
            topics = "${kafka.topics.product-reservation-failed}-dlt",
            groupId = "order-service-dlt-group"
    )
    public void handleProductReservationFailedDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = "kafka_offset", required = false) Long offset,
            @Header(name = "kafka_exceptionMessage", required = false) String exception) {

        log.error("=== DEAD LETTER: product-reservation-failed-dlt ===\n" +
                        "Partition: {}\n" +
                        "Offset: {}\n" +
                        "Exception: {}\n" +
                        "Message: {}",
                partition, offset, exception, message);

        recordAndAlert("product-reservation-failed-dlt", message, exception);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts orderId from the message payload for easier tracking and alerting.
     *
     * <p>Attempts to parse the message as JSON and extract the {@code orderId} field.
     * Returns empty if the message is malformed or doesn't contain an orderId.
     *
     * @param message the raw message payload
     * @return optional orderId if present and parseable
     */
    private Optional<String> extractOrderId(String message) {
        try {
            JsonNode json = MAPPER.readTree(message);
            if (json.has("orderId")) {
                return Optional.of(json.get("orderId").asText());
            }
        } catch (Exception e) {
            log.debug("Could not parse message to extract orderId", e);
        }
        return Optional.empty();
    }

    /**
     * Records the failure and sends alerts to monitoring systems.
     *
     * <p>This is the main entry point for post-failure handling. It can be extended to:
     * <ul>
     *   <li>Persist failures to a database for manual review</li>
     *   <li>Send email or Slack notifications</li>
     *   <li>Create PagerDuty incidents for critical failures</li>
     *   <li>Increment failure counters in monitoring systems (Prometheus, CloudWatch)</li>
     * </ul>
     *
     * @param topic     the DLT topic name
     * @param message   the message payload
     * @param exception the exception message
     */
    private void recordAndAlert(String topic, String message, String exception) {
        String orderId = extractOrderId(message).orElse("UNKNOWN");

        log.warn("[DLT ALERT] Dead letter message detected for orderId={}, topic={}, exception={}",
                orderId, topic, exception);

        // TODO: Implement actual alerting
        // Examples:
        // 1. Save to database:
        //    dlqMessageRepository.save(DlqMessage.builder()
        //        .topic(topic)
        //        .orderId(orderId)
        //        .message(message)
        //        .exception(exception)
        //        .recordedAt(LocalDateTime.now())
        //        .status(MessageStatus.PENDING_REVIEW)
        //        .build());
        //
        // 2. Send Slack notification:
        //    slackService.sendAlert("DLT Alert", String.format(
        //        "Order %s failed in topic %s. Exception: %s",
        //        orderId, topic, exception));
        //
        // 3. Send PagerDuty alert for critical failures:
        //    if (exception.contains("Database") || exception.contains("Timeout")) {
        //        pagerDutyService.createIncident(
        //            String.format("Critical: Order %s processing failed", orderId));
        //    }
        //
        // 4. Increment Prometheus counter:
        //    meterRegistry.counter("kafka.dlt.messages.received", "topic", topic).increment();
    }
}
