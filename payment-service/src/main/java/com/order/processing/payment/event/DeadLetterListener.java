package com.order.processing.payment.event;

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
 * {@link com.order.processing.payment.config.KafkaErrorHandlerConfig}), it is
 * automatically sent to a DLT topic. This listener consumes from those DLTs
 * and performs investigation, alerting, and potential manual recovery.
 *
 * <p><strong>DLT Topic Naming Convention:</strong>
 * <pre>
 * Original Topic        →  DLT Topic
 * ──────────────────────────────────────────────
 * payment-initiated     →  payment-initiated-dlt
 * </pre>
 *
 * <p><strong>Consumer Group:</strong> {@code payment-service-dlt-group} (dedicated group
 * to keep DLT processing isolated from main event processing)
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
     * Consumes messages that failed on {@code payment-initiated} and were sent to
     * the {@code payment-initiated-dlt} topic.
     *
     * <p>This would typically handle cases where:
     * <ul>
     *   <li>Idempotency check (looking up existing payment) failed repeatedly.</li>
     *   <li>Payment processing or persistence failed even with exponential retries.</li>
     *   <li>Unexpected deserialization, NullPointerException, or other exceptions.</li>
     *   <li>Payment gateway integration repeatedly timed out or failed.</li>
     * </ul>
     *
     * <p><strong>Recovery Actions:</strong>
     * <ol>
     *   <li>Log with full context (partition, offset, exception, orderId).</li>
     *   <li>Record failure in a monitoring/alerting system.</li>
     *   <li>Send alert to operations (e.g., Slack, PagerDuty) — critical since payments are involved.</li>
     *   <li>Store in a failure tracking database for manual review by finance team.</li>
     *   <li>Check for partial payment state (charged but not recorded) for recovery.</li>
     *   <li>Consider refund if payment was taken but saga cannot proceed.</li>
     * </ol>
     *
     * @param message   the message payload (typically JSON as String)
     * @param partition the original Kafka partition
     * @param offset    the message offset in the partition
     * @param exception the exception message from the failed listener
     */
    @KafkaListener(
            topics = "${kafka.topics.payment-initiated}-dlt",
            groupId = "payment-service-dlt-group"
    )
    public void handlePaymentInitiatedDlt(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(name = "kafka_offset", required = false) Long offset,
            @Header(name = "kafka_exceptionMessage", required = false) String exception) {

        log.error("=== DEAD LETTER: payment-initiated-dlt ===\n" +
                        "Partition: {}\n" +
                        "Offset: {}\n" +
                        "Exception: {}\n" +
                        "Message: {}",
                partition, offset, exception, message);

        recordAndAlert("payment-initiated-dlt", message, exception);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts orderId and userId from the message payload for easier tracking and alerting.
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
     *   <li>Persist failures to a database for manual review and audit trail</li>
     *   <li>Send email or Slack notifications to payments team</li>
     *   <li>Create high-priority PagerDuty incidents (payment failures are critical!)</li>
     *   <li>Increment failure counters in monitoring systems (Prometheus, CloudWatch)</li>
     *   <li>Trigger reconciliation checks with payment gateway</li>
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

        // TODO: Implement actual alerting (CRITICAL for payments!)
        // Examples:
        // 1. Save to database with audit trail:
        //    dlqMessageRepository.save(DlqMessage.builder()
        //        .topic(topic)
        //        .orderId(orderId)
        //        .message(message)
        //        .exception(exception)
        //        .recordedAt(LocalDateTime.now())
        //        .status(MessageStatus.PENDING_REVIEW)
        //        .requiresManualIntervention(true)  // Payment failures always require review
        //        .build());
        //
        // 2. Send CRITICAL Slack notification:
        //    slackService.sendCriticalAlert("PAYMENT DLT", String.format(
        //        "🚨 Payment failed for Order %s. Exception: %s. Immediate review required!",
        //        orderId, exception));
        //
        // 3. Create HIGH-priority PagerDuty incident:
        //    pagerDutyService.createIncident(
        //        String.format("CRITICAL: Payment processing failed for Order %s", orderId),
        //        Urgency.HIGH,
        //        "payments-on-call-schedule");
        //
        // 4. Increment Prometheus counter and gauge:
        //    meterRegistry.counter("kafka.dlt.messages.received", "topic", topic).increment();
        //    meterRegistry.gauge("kafka.dlt.pending.payments", orderId, 1);
        //
        // 5. Check payment gateway for partial charges:
        //    Optional<PaymentFromGateway> charge = paymentGateway.lookupByOrderId(orderId);
        //    if (charge.isPresent()) {
        //        log.warn("Partial charge detected! Amount: {}, Status: {}", 
        //            charge.get().getAmount(), charge.get().getStatus());
        //        // May need to trigger refund or manual reconciliation
        //    }
    }
}
